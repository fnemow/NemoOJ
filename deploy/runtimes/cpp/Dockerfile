ARG BASE_IMAGE
FROM ${BASE_IMAGE}
ARG GCC_VERSION
COPY nlohmann/json.hpp /usr/local/include/nlohmann/json.hpp
COPY LICENSE.MIT /usr/local/share/licenses/nlohmann-json/LICENSE.MIT
RUN echo '9bea4c8066ef4a1c206b2be5a36302f8926f7fdc6087af5d20b417d0cf103ea6  /usr/local/include/nlohmann/json.hpp' | sha256sum -c -
RUN test "$(g++ -dumpfullversion)" = "${GCC_VERSION}" && test "${GCC_VERSION%%.*}" = 14
RUN test -x /usr/local/bin/g++ && test -x /bin/cat && test -x /bin/sleep && id nobody
WORKDIR /app
ENTRYPOINT []
USER nobody

