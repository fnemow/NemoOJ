ARG BASE_IMAGE
FROM ${BASE_IMAGE}
ARG PYTHON_VERSION
RUN python3 -c "import platform; assert platform.python_version() == '${PYTHON_VERSION}'; assert platform.python_version().startswith('3.12.')"
RUN test -x /usr/local/bin/python3 && test -x /bin/cat && test -x /bin/sleep && id nobody
WORKDIR /app
ENTRYPOINT []
USER nobody

