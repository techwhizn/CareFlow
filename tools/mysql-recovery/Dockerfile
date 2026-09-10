ARG MYSQL_IMAGE=mysql:8.4.6
FROM ${MYSQL_IMAGE} AS unpack
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
USER root
RUN microdnf install -y cpio && microdnf clean all
WORKDIR /recovery-tool
RUN test "$(uname -m)" = x86_64 \
 && curl --fail --location --retry 3 --output client.rpm https://repo.mysql.com/yum/mysql-8.4-community/el/9/x86_64/mysql-community-client-8.4.6-1.el9.x86_64.rpm \
 && rpmkeys --import /etc/pki/rpm-gpg/RPM-GPG-KEY-mysql \
 && rpmkeys --checksig --verbose client.rpm \
 && rpm2cpio client.rpm | cpio -idmu
FROM ${MYSQL_IMAGE}
COPY --from=unpack /recovery-tool/usr/bin/mysqlbinlog /usr/local/bin/mysqlbinlog
COPY --from=unpack /recovery-tool/usr/share/doc/mysql-community-client /usr/share/doc/mysql-community-client
RUN mysqlbinlog --version
ENTRYPOINT ["mysqlbinlog"]
