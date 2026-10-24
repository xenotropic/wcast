FROM mcr.microsoft.com/devcontainers/java:17-bookworm

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk

# Permanently install adb/iptables plus the Android SDK toolchain (command-line
# tools + platform android-34 + build-tools 34.0.0), so a container rebuild does
# not lose the toolchain the Gradle build depends on. local.properties points at
# ${ANDROID_HOME}.
RUN apt-get update && apt-get install -y --no-install-recommends \
    adb \
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
    && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" "platform-tools" \
    && chown -R vscode:vscode /opt/android-sdk

# Cache git credentials in-memory for 8 hours so pushes don't re-prompt within
# a session, but the fine-grained PAT is never written to disk and can't be
# replayed after 8h (or a container rebuild). Run as the container's vscode
# user (the runtime user) so `--global` lands in /home/vscode/.gitconfig. The
# Dev Containers extension injects a host-keychain credential helper into
# /etc/gitconfig at container start; GIT_CONFIG_NOSYSTEM=1 (set in
# .devcontainer/devcontainer.json) disables it so git never falls back to the
# indefinite host keychain.
RUN su - vscode -c "git config --global credential.helper 'cache --timeout=28800'"

# Pre-create the VS Code Server directory and assign user permissions
# to ensure Docker volume mounts initialize with vscode ownership.
RUN mkdir -p /home/vscode/.vscode-server/data/User/globalStorage \
    && chown -R vscode:vscode /home/vscode/.vscode-server

# The base devcontainer image ships a themed ~/.bashrc that installs a
# `trap 'preexec' DEBUG` plus a `precmd` in PROMPT_COMMAND, both of which emit
# terminal-title escape sequences. Those clobber the OSC 633 markers that
# Cline/VS Code shell integration uses to detect "command finished", causing
# commands to hang on a timing heuristic. Strip the xterm trap block (keeping the
# git-branch prompt) so shell integration can complete reliably.
RUN sed -i '/^# Check if the terminal is xterm$/,/^fi$/d' /home/vscode/.bashrc \
    && chown vscode:vscode /home/vscode/.bashrc