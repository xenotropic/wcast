FROM mcr.microsoft.com/devcontainers/java:17-bullseye

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk

# Permanently install adb/iptables plus the Android SDK toolchain (command-line
# tools + platform android-34 + build-tools 34.0.0), so a container rebuild does
# not lose the toolchain the Gradle build depends on. local.properties points at
# ${ANDROID_HOME}.
RUN apt-get update && apt-get install -y --no-install-recommends \
    android-tools-adb \
    curl \
    iptables \
    unzip \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-9862592_latest.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
    && mv /tmp/cmdline-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip \
    && yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null \
    && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# Cache git credentials in-memory for 8 hours so pushes don't re-prompt each
# time. Run as the container's vscode user (the runtime user) so `--global`
# lands in /home/vscode/.gitconfig instead of root's.
RUN su - vscode -c "git config --global credential.helper 'cache --timeout=28800'"
    