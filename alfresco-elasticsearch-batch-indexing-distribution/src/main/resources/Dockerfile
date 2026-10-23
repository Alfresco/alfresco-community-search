# More infos about this image: https://github.com/Alfresco/alfresco-docker-base-java
ARG JAVA_BASE_IMAGE=alfresco/alfresco-base-java:jre17-rockylinux9@sha256:f98833508b7be8c4b44a25450f9faac44cacfdc075f2295e02836b93fd05bb9c
FROM ${JAVA_BASE_IMAGE}
ARG JAR_FILE=*-app.jar
WORKDIR /opt
COPY ${JAR_FILE} app.jar
COPY THIRD-PARTY.txt THIRD-PARTY.txt
COPY README.md README.md
COPY LICENSE.txt LICENSE-GPL-3.0.txt ATTRIBUTION-NOTICE.MD ./
COPY licenses licenses
# Postgres driver is bundled in the fat jar. Drivers for other databases (Oracle, SQL Server)
# can be supplied at runtime by mounting them into /opt/db-drivers; PropertiesLauncher picks
# them up via the manifest's Loader-Path: db-drivers/ entry.
RUN mkdir -p /opt/db-drivers
VOLUME /opt/db-drivers
ENTRYPOINT exec java $JAVA_OPTS -jar app.jar
