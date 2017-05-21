package com.e_gineering.maven.gitflowhelper;

import java.io.IOException;
import java.util.Properties;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.manager.ScmManager;
import org.codehaus.plexus.util.cli.CommandLineUtils;

import com.e_gineering.maven.gitflowhelper.properties.ExpansionBuffer;
import com.e_gineering.maven.gitflowhelper.properties.PropertyResolver;

/**
 * Abstracts Per-Branch builds & Logging
 */
public abstract class AbstractGitflowBranchMojo extends AbstractMojo {

    public static final String MASTER_DEFAULT_BRANCH_PATTERN = "(origin/)?master";
    public static final String SUPPORT_DEFAULT_BRANCH_PATTERN = "(origin/)?support/(.*)";

    private Properties systemEnvVars = new Properties();

    private PropertyResolver resolver = new PropertyResolver();


    @Component
    protected MavenProject project;

    @Component
    protected ScmManager scmManager;

    @Parameter(defaultValue = MASTER_DEFAULT_BRANCH_PATTERN, property = "masterBranchPattern", required = true)
    private String masterBranchPattern;

    @Parameter(defaultValue = SUPPORT_DEFAULT_BRANCH_PATTERN, property = "supportBranchPattern", required = true)
    private String supportBranchPattern;

    @Parameter(defaultValue = "(origin/)?release/(.*)", property = "releaseBranchPattern", required = true)
    private String releaseBranchPattern;

    @Parameter(defaultValue = "(origin/)?hotfix/(.*)", property = "hotfixBranchPattern", required = true)
    private String hotfixBranchPattern;

    @Parameter(defaultValue = "(origin/)?develop", property = "developmentBranchPattern", required = true)
    private String developmentBranchPattern;

    // @Parameter tag causes property resolution to fail for patterns containing ${env.}. Default provided in execute();
    @Parameter(property = "gitBranchExpression", required = false)
    private String gitBranchExpression;

    @Parameter(alias = "retainedExecutions", required = false)
    private String[] retainedExecutions;

    protected abstract void execute(final GitBranchType type, final String gitBranch, final String branchPattern) throws MojoExecutionException, MojoFailureException;

    /**
     * Method exposing Property Resolving for subclasses.
     *
     * @param expression
     * @return
     */
    protected String resolveExpression(final String expression) {
        return resolver.resolveValue(expression, project.getProperties(), systemEnvVars);
    }

    private void logExecute(final GitBranchType type, final String gitBranch, final String branchPattern) throws MojoExecutionException, MojoFailureException {
        getLog().debug("Building for GitBranchType: " + type.name() + ". gitBranch: '" + gitBranch + "' branchPattern: '" + branchPattern + "'");
        execute(type, gitBranch, branchPattern);
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (gitBranchExpression == null) {
            gitBranchExpression = ScmUtils.resolveBranchOrExpression(scmManager, project, getLog());
        }

        try {
            systemEnvVars = CommandLineUtils.getSystemEnvVars();
        } catch (IOException ioe) {
            throw new MojoExecutionException("Unable to read System Envirionment Variables: ", ioe);
        }

        // Try to resolve the gitBranchExpression to an actual Value...
        String gitBranch = resolveExpression(gitBranchExpression);
        ExpansionBuffer eb = new ExpansionBuffer(gitBranch);

        if (!gitBranchExpression.equals(gitBranch) || getLog().isDebugEnabled()) { // Resolves Issue #9
            getLog().debug("Resolved gitBranchExpression: '" + gitBranchExpression + " to '" + gitBranch + "'");
        }

        if (!eb.hasMoreLegalPlaceholders()) {
            /*
             * (/origin/)?master goes to the maven 'release' repo.
             * (/origin/)?release/(.*) , (/origin/)?hotfix/(.*) , and (/origin/)?bugfix/(.*) go to the maven 'stage' repo.
             * (/origin/)?develop goes to the 'snapshot' repo.
             * All other builds will use the default semantics for 'deploy'.
             */
            if (gitBranch.matches(masterBranchPattern)) {
                logExecute(GitBranchType.MASTER, gitBranch, masterBranchPattern);
            } else if (gitBranch.matches(supportBranchPattern)) {
                logExecute(GitBranchType.SUPPORT, gitBranch, supportBranchPattern);
            } else if (gitBranch.matches(releaseBranchPattern)) {
                logExecute(GitBranchType.RELEASE, gitBranch, releaseBranchPattern);
            } else if (gitBranch.matches(hotfixBranchPattern)) {
                logExecute(GitBranchType.HOTFIX, gitBranch, hotfixBranchPattern);
            } else if (gitBranch.matches(developmentBranchPattern)) {
                logExecute(GitBranchType.DEVELOPMENT, gitBranch, developmentBranchPattern);
            } else {
                logExecute(GitBranchType.OTHER, gitBranch, null);
            }
        } else {
            logExecute(GitBranchType.UNDEFINED, gitBranch, null);
        }
    }

    public void setRetainedExecutions(String[] retainedExecutions) {
        this.retainedExecutions = retainedExecutions;
    }
}
