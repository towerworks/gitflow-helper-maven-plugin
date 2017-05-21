package com.e_gineering.maven.gitflowhelper;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.MojoDescriptorCreator;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.prefix.NoPluginFoundForPrefixException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.manager.ScmManager;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import com.e_gineering.maven.gitflowhelper.properties.PropertyResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

import static com.e_gineering.maven.gitflowhelper.AbstractGitflowBranchMojo.MASTER_DEFAULT_BRANCH_PATTERN;
import static com.e_gineering.maven.gitflowhelper.AbstractGitflowBranchMojo.SUPPORT_DEFAULT_BRANCH_PATTERN;
import static com.e_gineering.maven.gitflowhelper.MasterPromoteExtension.GitflowHelperInfo.NO_PRUNING;

/**
 * Maven extension which removes (skips) undesired plugins from the build reactor when running on a master branch.
 * <p/>
 * Essentially, enables using the master branch as a 'promotion' branch.
 */
@Component(role = AbstractMavenLifecycleParticipant.class, hint = "promote-master")
public class MasterPromoteExtension extends AbstractMavenLifecycleParticipant {

    @Requirement
    private MojoDescriptorCreator descriptorCreator;

    @Requirement
    private Logger logger;

    @Requirement
    protected ScmManager scmManager;

    /**
     * Special symbol to signal that all executions should be matched when pruning.
     */
    private static final String GOAL_WILDCARD = "*";
    /**
     * Special symbol to signal that all goals should be matched when pruning.
     */
    private static final String EXECUTION_ID_WILDCARD = "*";


    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        final Multimap<String, String> sessionExecutionsToRetain = createPluginExecutionsMap(session, session.getGoals());

        for (MavenProject project : session.getProjects()) {
            final GitflowHelperInfo gitflowHelperInfo = extractGitflowHelperInfo(project);
            if (!gitflowHelperInfo.isPruneBuild()) {
                continue;
            }

            final Multimap<String, String> projectExecutionsToRetain = ImmutableMultimap.<String, String>builder().
                    putAll(sessionExecutionsToRetain).
                    putAll(createPluginExecutionsMap(session, gitflowHelperInfo.getExecutionsToRetain())).
                    build();

            for (Iterator<Plugin> it = project.getBuildPlugins().iterator(); it.hasNext(); ) {
                final Plugin plugin = it.next();
                final Collection<String> executions = projectExecutionsToRetain.get(plugin.getKey());
                final boolean retained;
                if (executions.isEmpty()) {
                    retained = false;
                    it.remove();
                } else if (ImmutableList.of(EXECUTION_ID_WILDCARD).equals(executions)) {
                    retained = true;
                } else {
                    // remove plugin executions that that are not in the list of executions to retain
                    for (Iterator<PluginExecution> exIt = plugin.getExecutions().iterator(); exIt.hasNext(); ) {
                        if (!executions.contains(exIt.next().getId())) {
                            plugin.flushExecutionMap();
                            exIt.remove();
                        }
                    }
                    if (!(retained = !plugin.getExecutions().isEmpty())) {
                        it.remove();
                    }
                }

                if (retained) {
                    logger.info("gitflow-helper-maven-plugin - in project: " + project.getName() + " retained plugin: " + plugin.getKey() + "@" + plugin.getExecutionsAsMap().keySet());
                } else {
                    logger.info("gitflow-helper-maven-plugin - in project: " + project.getName() + " removed plugin: " + plugin.getKey());
                }
            }
        }
    }

    /**
     * Create a map from plugin key to execution id for the supplied goals.
     *
     * @param session the maven session, needed to resolve plugins via plugin groups
     * @param goals   the goals to process
     * @return an immutable multimap from plugin key to execution id
     */
    private Multimap<String, String> createPluginExecutionsMap(MavenSession session, List<String> goals) {
        ImmutableMultimap.Builder<String, String> executionsToRetainBuilder = ImmutableMultimap.builder();

        for (String goal : goals) {
            final String[] parts = goal.split(":");

            switch (parts.length) {
                case 2:
                    try {
                        final Plugin plugin = descriptorCreator.findPluginForPrefix(parts[0], session);
                        executionsToRetainBuilder.put(plugin.getKey(), getExecutionId(parts[1]));
                    } catch (NoPluginFoundForPrefixException ex) {
                        logger.warn("gitflow-helper-maven-plugin: Unable to resolve project plugin for prefix: " + parts[0] + " for goal: " + goal);
                    }
                    break;
                case 3:
                    executionsToRetainBuilder.put(parts[0] + ":" + parts[1], getExecutionId(parts[2]));
                    break;
                case 4:
                    executionsToRetainBuilder.put(parts[0] + ":" + parts[1], getExecutionId(parts[3]));
                    break;
            }
        }

        return executionsToRetainBuilder.build();
    }


    /**
     * Extract the optional execution id from a plugin goal.
     *
     * @param goal the plugin goal
     * @return the execution id if defined or "default-cli"
     */
    // see https://git-wip-us.apache.org/repos/asf?p=maven.git;a=blobdiff;f=maven-core/src/main/java/org/apache/maven/lifecycle/internal/DefaultLifecycleExecutionPlanCalculator.java;h=0f060dc943915622264d0d1611696e24dec75403;hp=a5db25f7e5ddb00839091c4eecb32c4666a336e9;hb=ee7dbab69dd87d219031b0715105527cdbf12639;hpb=cd52e5b51e8e986b6daea8c0b56dd61968410695
    private String getExecutionId(String goal) {
        final int idx;
        return 0 < (idx = goal.indexOf('@')) ? goal.substring(idx + 1) : "default-cli";
    }


    /**
     * Collected information derived from the gitflow-helper configurations.
     */
    static class GitflowHelperInfo {
        private final boolean pruneBuild;
        private final List<String> executionsToRetain;

        static final GitflowHelperInfo NO_PRUNING = new GitflowHelperInfo(false, ImmutableList.<String>of());

        /**
         * Create a new instance of {@code GitflowHelperInfo}.
         *
         * @param pruneBuild         if {@code true} the build should be pruned
         * @param executionsToRetain the list of executions
         */
        GitflowHelperInfo(boolean pruneBuild, List<String> executionsToRetain) {
            this.pruneBuild = pruneBuild;
            this.executionsToRetain = executionsToRetain;
        }

        boolean isPruneBuild() {
            return pruneBuild;
        }

        List<String> getExecutionsToRetain() {
            return executionsToRetain;
        }
    }

    /**
     * Extract a {@link GitflowHelperInfo} from the supplied project. If no gitflow-helper plugin was found in {@code project}, {@link GitflowHelperInfo#NO_PRUNING} is returned
     *
     * @param project the project to process
     * @return the extracted information
     * @throws MavenExecutionException if anything went wrong
     */
    GitflowHelperInfo extractGitflowHelperInfo(MavenProject project) throws MavenExecutionException {
        for (Plugin plugin : project.getBuildPlugins()) {
            if (plugin.getKey().equals("ch.towerworks:gitflow-helper-maven-plugin")) {
                logger.debug("gitflow-helper-maven-plugin found in project: [" + project.getName() + "]");
                return extractGitflowHelperInfo(project, plugin);
            }
        }
        return NO_PRUNING;
    }

    /**
     * Extract a {@link GitflowHelperInfo} from the supplied project and gitflow-helper plugin.
     * <p>
     * Whether the build should be pruned is determined as the conjunction of:
     * <ul>
     * <li>the result of invoking {@link #shouldPruneBuild(MavenProject, String, Object[]) shouldPruneBuild}, and</li>
     * <li>whether the {@code }promote-master} goal is present in {@code gitflowHelperPlugin}</li>
     * </ul>
     * </p>
     *
     * @param project             the project to process
     * @param gitflowHelperPlugin the gitflow-helper plugin found in {@code project}
     * @return the extracted information
     * @throws MavenExecutionException if anything went wrong
     */
    GitflowHelperInfo extractGitflowHelperInfo(MavenProject project, Plugin gitflowHelperPlugin) throws MavenExecutionException {
        if (gitflowHelperPlugin.getExecutions().isEmpty()) {
            return shouldPruneBuild(project, EXECUTION_ID_WILDCARD, new Object[]{gitflowHelperPlugin.getConfiguration()})
                    ? new GitflowHelperInfo(true, ImmutableList.of("ch.towerworks:gitflow-helper-maven-plugin:" + GOAL_WILDCARD))
                    : NO_PRUNING;
        }

        final ImmutableList.Builder<String> retainedExecutions = ImmutableList.builder();
        boolean pruneBuild = false;

        for (PluginExecution each : gitflowHelperPlugin.getExecutions()) {
            if (shouldPruneBuild(project, each.getId(), new Object[]{each.getConfiguration(), gitflowHelperPlugin.getConfiguration()})
                    && each.getGoals().contains("promote-master")) {
                pruneBuild = true;
                retainedExecutions.add("ch.towerworks:gitflow-helper-maven-plugin:" + GOAL_WILDCARD + "@" + each.getId());
            }
        }
        if (pruneBuild) {
            retainedExecutions.addAll(extractConfigList("retainedExecutions", "retainedExecution", gitflowHelperPlugin.getConfiguration()));
            retainedExecutions.add("deploy:deploy@" + EXECUTION_ID_WILDCARD);
            return new GitflowHelperInfo(true, retainedExecutions.build());
        }
        return NO_PRUNING;
    }

    /**
     * Determines whether the build should be pruned based on the supplied project and configurations.
     *
     * @param project        the project to process, used in the determination of the git branch
     * @param executionId    the execution id, only used for debug logging
     * @param configurations the configurations in which to lookup configuration values
     * @return {@code true} if the build should be pruned, {@code false} otherwise
     * @throws MavenExecutionException if anything went wrong
     */
    boolean shouldPruneBuild(MavenProject project, String executionId, Object[] configurations) throws MavenExecutionException {
        final String gitBranch = gitBranch(project, configurations);

        if (StringUtils.isBlank(gitBranch)) {
            return false;
        }

        final String masterBranchPattern = extractConfigValue("masterBranchPattern", configurations, MASTER_DEFAULT_BRANCH_PATTERN);
        if (logger.isDebugEnabled()) {
            logger.debug("gitflow-helper execution " + executionId + ": master branch pattern=" + masterBranchPattern);
        }
        if (gitBranch.matches(masterBranchPattern)) {
            return true;
        }

        final String supportBranchPattern = extractConfigValue("supportBranchPattern", configurations, SUPPORT_DEFAULT_BRANCH_PATTERN);
        if (logger.isDebugEnabled()) {
            logger.debug("gitflow-helper execution " + executionId + ": support branch pattern=" + supportBranchPattern);
        }
        return gitBranch.matches(supportBranchPattern);
    }

    /**
     * Determines the git branch of the supplied project based on the supplied configurations.
     *
     * @param project        the project
     * @param configurations the configurations
     * @return the optional git branch name
     * @throws MavenExecutionException if anything went wrong
     */
    private String gitBranch(MavenProject project, Object[] configurations) throws MavenExecutionException {
        // FIXME: 20.05.17 this code is a duplication
        // FIXME: 20.05.17 memoize global branch config
        // FIXME: 20.05.17 consider using Either to return global branch xor pattern
        String gitBranchExpression = extractConfigValue("gitBranchExpression", configurations, null);

        if (gitBranchExpression == null) {
            logger.debug("Using default branch expression resolver.");
            gitBranchExpression = ScmUtils.resolveBranchOrExpression(scmManager, project, new DefaultLog(logger));
        }
        logger.debug("Git Branch Expression: " + gitBranchExpression);

        Properties systemEnvVars = null;
        try {
            systemEnvVars = CommandLineUtils.getSystemEnvVars();
        } catch (IOException ioe) {
            throw new MavenExecutionException("Unable to read System Environment Variables: ", ioe);
        }
        PropertyResolver pr = new PropertyResolver();
        String gitBranch = pr.resolveValue(gitBranchExpression, project.getProperties(), systemEnvVars);
        logger.info("gitflow-helper-maven-plugin: Build Extension resolved gitBranchExpression: " + gitBranchExpression + " to: " + gitBranch);
        return gitBranch;
    }

    private String extractConfigValue(String parameter, Object[] configurations, String defaultValue) {
        for (Object each : configurations) {
            final Xpp3Dom child = ((Xpp3Dom) each).getChild(parameter);
            if (child != null) {
                return child.getValue();
            }
        }
        return defaultValue;
    }

    private List<String> extractConfigList(String parameterName, String elementName, Object configuration) {
        final ImmutableList.Builder<String> answer = ImmutableList.builder();

        final Xpp3Dom parameter = ((Xpp3Dom) configuration).getChild(parameterName);
        if (parameter != null) {
            for (Xpp3Dom each : parameter.getChildren(elementName)) {
                answer.add(each.getValue());
            }
        }
        return answer.build();
    }
}