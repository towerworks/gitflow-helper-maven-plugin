#!/usr/bin/env bash
mvn deploy:deploy-file \
  -Dfile=target/gitflow-helper-maven-plugin-1.7.1.jar \
  -DrepositoryId=milestones-s3 \
  -Durl=s3://repo-towerworks-ch/lib-milestones \
  -DpomFile=pom.xml \
  -DgroupId=ch.towerworks \
  -DartifactId=gitflow-helper-maven-plugin \
  -Dversion=1.7.1 \
  -DgeneratePom=false \
  -Dpackaging=maven-plugin