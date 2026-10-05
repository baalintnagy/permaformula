FROM maven:3-eclipse-temurin-25

WORKDIR /usr/src/app

COPY . /usr/src/app
RUN mvn package dependency:build-classpath -Dmdep.outputFile=target/render-classpath.txt
ENV PLAYWRIGHT_BROWSERS_PATH=/usr/src/app/.runtime/browsers
RUN java -cp "$(cat target/render-classpath.txt)" com.microsoft.playwright.CLI install --with-deps chromium --no-shell
RUN apt-get update && apt-get install -y --no-install-recommends xvfb xauth && rm -rf /var/lib/apt/lists/*

ENV PORT 5000
EXPOSE $PORT
CMD [ "sh", "-c", "exec xvfb-run -a java --enable-native-access=ALL-UNNAMED -jar target/permmath-service-1.0.jar --server.port=${PORT}" ]
