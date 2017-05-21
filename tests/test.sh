#!/usr/bin/env bash

set -e

maven_versions=(${MVN_VERSIONS:-3.5.0 3.3.9 3.3.3 3.3.1 3.2.5 3.2.3 3.2.2 3.2.1 3.1.1 3.1.0})

declare pom_file=$(pwd)/test-pom.xml
declare global_settings_file=$(pwd)/test-global-settings.xml
declare settings_file=$(pwd)/test-settings.xml

declare mvn_cache_dir=$(pwd)/cache

run_mvn() {
    #fixme mvnDebug support
    mvn $maven_args $*
}

run_mvn_debug() {
    mvnDebug $maven_args $*
}

create_repo() {
    declare repo_dir=${1}
    mkdir -p ${repo_dir}
    rm -rf ${repo_dir}/*
    mkdir -p ${repo_dir}/release
    mkdir -p ${repo_dir}/stage
    mkdir -p ${repo_dir}/snapshot
}

install_mvn() {
    declare v=$1
    declare archive=apache-maven-${v}-bin.tar.gz

    if [ -f $mvn_cache_dir/$archive ] ; then
        cp $mvn_cache_dir/$archive .
    else
        curl -O https://archive.apache.org/dist/maven/maven-3/${v}/binaries/$archive
        cp $archive $mvn_cache_dir
    fi

    gunzip apache-maven-${v}-bin.tar.gz
    tar xf apache-maven-${v}-bin.tar

    export MAVEN_HOME=$(pwd)
    export path="$MAVEN_HOME/apache-maven-$v/bin:$original_path"

    export MAVEN_HOME=$(pwd)
    export path="$MAVEN_HOME/apache-maven-$v/bin:$path"
}

prepare_test() {
    declare version=$1
    echo ${version}

    [ -d $version ] && chmod -R +w ${version} && rm -rf ${version}/*

     mkdir -p ${version}

    cd ${version}

    install_mvn $version
    create_repo mvn_repo

    maven_args="-gs $global_settings_file -s $settings_file -Drepo=file://$(pwd)/mvn_repo -B"
    #        mvnDebug ${maven_args} deploy -Dgit-branch=$branch

#    grep $version < <(run_mvn -version)
    run_mvn ${maven_args} -version
}

prepare_src() {
    echo src $1
    declare src_dir=$1
    mkdir -p $src_dir
    cp $pom_file $src_dir/pom.xml
}

prepare_src_git() {
    echo src_git $1
    declare git_dir=$2

    mkdir -p ${git_dir}

    cd ${git_dir}
    git init

    git checkout -b feature
    cp $pom_file pom.xml
    git add .
    git commit -m "initial"

    git checkout -b develop
    git merge feature

    git checkout -b release/1.0.0
    git merge develop
    run_mvn versions:set -DnewVersion=1.0.0 versions:commit
    git add .
    git commit -m "merged develop, version set to 1.0.0"

    git checkout -b master
    git merge release/1.0.0

    cd -
 }


run_tests() {
    declare version=$1
    declare src_dir=$2

    cd $src_dir

    echo ---- $version: no branch
    run_mvn deploy

    #fixme  support and hotfix: correct branch names??
    for branch in ${BRANCHES:-feature develop release/1.0.0 master support hotfix} ; do
        echo ---- $version: $branch

        [ $branch = release/1.0.0 ] && mvn ${maven_args} versions:set -DnewVersion=1.0.0 versions:commit

        run_mvn ${GOALS:-deploy} -Dgit-branch=$branch
    done

    cd -
}

run_tests_git() {
    declare version=$1
    declare git_dir=$2
    declare goals="$3"

    cd $git_dir


#    echo ---- $version: no branch
#    run_mvn deploy

    #fixme  support and hotfix: correct branch names??
    for branch in ${BRANCHES:-feature develop release/1.0.0 master} ; do
        echo ---- $version: $branch

        git checkout $branch
        git status
        run_mvn $goals -P git -Denv.GIT_URL=file://$(pwd)
    done

    cd -
}

#declare work_dir=/tmp/gitflow-helper-maven-plugin-tests
declare work_dir=$(pwd)/work/gitflow-helper-maven-plugin-tests

[ -d $work_dir ] && chmod -R +w $work_dir && rm -r $work_dir

mkdir -p $work_dir
cd $work_dir

declare original_path="$PATH"

for v in ${maven_versions[@]}; do
    prepare_test $v

#    prepare_src project
#    run_tests $v project

    pwd

    prepare_src_git $v project_git
    run_tests_git $v project_git ${GOALS:-deploy}

    cd $work_dir
done



