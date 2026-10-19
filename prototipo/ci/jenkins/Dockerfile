# Jenkins con las herramientas del pipeline: JDK 21, Python 3.12, JUnit 5, JavaFX, Docker CLI, Helm, kubectl y Trivy.
FROM jenkins/jenkins:lts-jdk21
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-pip python3-venv curl ca-certificates gnupg junit5 openjfx docker.io \
 && rm -rf /var/lib/apt/lists/*
RUN pip3 install --break-system-packages pydantic lxml fastapi uvicorn httpx "mcp>=2" anthropic pytest hypothesis
RUN curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash \
 && curl -fsSLo /usr/local/bin/kubectl "https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
 && chmod +x /usr/local/bin/kubectl \
 && curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin
USER jenkins
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY casc.yaml /var/jenkins_home/casc.yaml
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc.yaml JAVA_OPTS=-Djenkins.install.runSetupWizard=false
