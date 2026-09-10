FROM public.ecr.aws/docker/library/mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26
# CareFlow uses mysqld and mysql, not mysqlsh or its bundled Python.
# Remove the optional package through RPM dependency management, preserving the
# upstream entrypoint, server, SQL client and license notices. Binlog export uses
# a separately verified recovery-tool image, as documented in operations.md.
RUN microdnf remove -y mysql-shell && microdnf clean all \
    && mysqld --version && mysql --version
