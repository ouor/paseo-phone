<p align="center">
  <img src="packages/website/public/logo.svg" width="64" height="64" alt="Paseo logo">
</p>

<h1 align="center">Paseo Phone</h1>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.ko.md">한국어</a>
</p>

<p align="center">
  <a href="https://github.com/ouor/paseo/releases">
    <img src="https://img.shields.io/github/v/release/ouor/paseo?include_prereleases&style=flat&logo=github" alt="Release">
  </a>
  <a href="https://github.com/getpaseo/paseo">
    <img src="https://img.shields.io/badge/upstream-getpaseo%2Fpaseo-555?logo=github" alt="Upstream Paseo">
  </a>
</p>

Paseo Phone turns one Android phone into your dev machine. The phone runs Debian, the [Paseo](https://github.com/getpaseo/paseo) daemon, and your coding agents. The phone app is the agent UI, and a desktop is a terminal that connects to the phone. It is a personal fork of Paseo, distributed only through GitHub Releases.

Structure: the app runs Debian 13 under proot (no root). A foreground service keeps the Paseo daemon (`127.0.0.1:6767`) and sshd (port `2222`) running. The Paseo app on the phone sees the daemon as "this device" without pairing. A desktop connects over SSH.

Upstream Paseo is an open source agentic development environment for desktop, mobile, web, and CLI. It runs Claude Code, Codex, Copilot, OpenCode, Pi, Antigravity, and Muse Code agents through one interface. Paseo Phone installs Claude Code, Codex, and OpenCode.

## Requirements

- An arm64 Android phone. No other ABI is built.
- About 4 GB free storage. Setup checks for 4 GB, peaks near 3.3 GB, and leaves about 2.4 GB.
- The phantom process killer turned off. With it on, Android kills the daemon, sshd, and proot. From a computer with adb:

  ```bash
  adb shell settings put global settings_enable_monitor_phantom_procs false
  ```

- Recommended: exempt Paseo Phone from battery optimization so work continues with the screen off.

The app detects both settings and warns you. It does not change them.

## Install

1. Download `paseo-phone-arm64.apk` from [Releases](https://github.com/ouor/paseo/releases). Tags are `phone-v<upstream version>-<n>`. The first is `phone-v0.14.0-1` (arm64, pre-release).
2. If you have an older build signed with a different key (the early unsigned/debug builds), uninstall it first. Android refuses the update otherwise. Uninstalling deletes the Linux environment, including repos and agent logins under `/root`.
3. Install the APK. The app is "Paseo Phone" (`io.github.ouor.paseophone`) and installs next to the regular Paseo app.

## First run

Open the app. It downloads and sets up Debian, Node.js, the Paseo daemon, and the agent CLIs. This takes about 6 minutes and needs a network connection. If a step fails, the screen shows the error and the setup log, and **Retry** resumes from the failed step.

When setup ends, the app opens on the "this device" host. Linux controls live in **Settings › Hosts › this device › Host**:

- **Linux**: start/stop, daemon status and restart, SSH server switch, keep-awake switch, logs.
- **Remote access**: address (Wi-Fi or Tailscale), `ssh` command, Paseo desktop URI, SSH config, host key fingerprint, authorized keys.
- **Exchange folder**: open the folder, copy the Linux path.
- **Reset Linux**: reinstall Debian. It keeps the daemon server ID, SSH host keys, and authorized keys. The rest of `/root` is deleted.

The persistent notification shows status and has **Stop** and a link to the logs.

## Log in to agents

Agent logins happen in a shell on the phone. Connect over SSH (next section), then run:

| Agent       | Command                                   | Note                                                                  |
| ----------- | ----------------------------------------- | --------------------------------------------------------------------- |
| Claude Code | `claude`, then `/login`                   | There is no browser in proot. Open the printed URL on another device. |
| Codex       | `codex login --device-auth`               |                                                                       |
| OpenCode    | `opencode auth login` (`/connect` in TUI) | API key or OAuth, per provider.                                       |

Codex's sandbox does not work under proot. Pick **Full Access** for Codex agents. The global agent instructions written to `/root/.claude/CLAUDE.md`, `/root/.codex/AGENTS.md`, and `/root/.config/opencode/AGENTS.md` say this too.

## Connect from a desktop

sshd accepts public keys only, as `root` on port `2222`, over IPv4. Use the same Wi-Fi or Tailscale.

1. In **Remote access › Authorized keys**, paste your desktop's public key.
2. Connect once from a terminal to accept the host key. Compare it with the fingerprint shown in the app.

   ```bash
   ssh -p 2222 root@<phone address>
   ```

3. Paseo desktop: add a Remote SSH host with the URI from **Remote access** (`ssh://root@<phone address>:2222`). The daemon listens only on loopback, so desktop Paseo always connects through SSH. It refuses unknown host keys, which is why step 2 comes first.
4. VS Code: copy the SSH config from **Remote access** into `~/.ssh/config` and connect with Remote-SSH.

The daemon has no password and no relay. Anything that reaches `127.0.0.1:6767` on the phone, including other apps, can use it.

## Exchange folder

`/storage/emulated/0/Paseo` on the phone is mounted at `/root/exchange` in Linux. Use it to move files between Linux and other apps. Keep repos under `/root`.

- It needs the "All files access" permission. That permission covers the whole app, so every program in Linux can read, write, and delete all of shared storage. It is off by default.
- After granting it, restart Linux from the prompt in the app.
- Symlinks, hard links, and the executable bit do not work there.

## Build from source

```bash
bash scripts/phone/build-apk.sh [--skip-tests] [--install] [--device <serial>]
```

It requires a clean working tree, builds the arm64 release APK, runs the module unit tests, signs with the owner's release key, and checks the certificate. Output is `artifacts/paseo-phone-arm64.apk`, the full log `artifacts/paseo-phone-build.log`. `--install` stops instead of uninstalling when the signature differs.

The signing files (`signing/release.p12`, `signing/keystore.properties`) are the owner's and are not in the repo. Without them the build fails at the start. For your own build, use your own key and change the expected certificate hash in the script.

## Staying current with upstream

This fork tracks upstream Paseo releases and is never merged back. To move to a new upstream release, merge its tag and walk the merge checklist in [architecture §4.3](docs/phone-dev-machine/architecture/architecture.md). The fork's edits to upstream files are marked `phone-fork:`; check them with `rg -n "phone-fork:" packages/app scripts`.

The daemon is the public `@getpaseo/cli` at the version in `packages/server/package.json`, and Node.js follows `.tool-versions`. After a merge, the app updates both on the phone at the next start. Agent CLIs and apt packages are not upgraded automatically.

## Limitations and non-goals

- arm64 only, Debian only. Shell access is documented and tested over SSH.
- File operations are much slower under proot. Heavy builds (such as Android Gradle builds) are out of scope.
- No access through outside servers, cloud hosts, or the Paseo relay. No daemon password or LAN-exposed daemon.
- No repo work in shared storage and no two-way sync.
- No Play Store release, no onboarding for general users, no CI.
- No start on boot. Agent CLIs and apt packages are upgraded by you.
- No remote push notifications (no Firebase).

## Docs

Design and process docs are in [docs/phone-dev-machine/](docs/phone-dev-machine/) (Korean):

- [original-plan.md](docs/phone-dev-machine/original-plan.md): what and why, non-goals
- [architecture/architecture.md](docs/phone-dev-machine/architecture/architecture.md): decisions, ports, paths, merge checklist
- [architecture/spike-report.md](docs/phone-dev-machine/architecture/spike-report.md): agents under proot, phantom process killer, speed
- [core/core-rootfs.md](docs/phone-dev-machine/core/core-rootfs.md): setup scripts, build script, sizes
- [ui/ui.md](docs/phone-dev-machine/ui/ui.md): where each feature lives in the app
- [qa/qa-report.md](docs/phone-dev-machine/qa/qa-report.md): QA results

Upstream Paseo docs are at [paseo.sh/docs](https://paseo.sh/docs).

## Credits and license

- [Paseo](https://github.com/getpaseo/paseo) by Mohamed Boudra: the daemon, app, and agent integrations. This fork follows its releases.
- [Kai](https://github.com/SimonSchubert/Kai) by Simon Schubert: the Linux layer (proot build and runtime code) is ported from Kai.
- [termux/proot](https://github.com/termux/proot) and [termux/proot-distro](https://github.com/termux/proot-distro): proot and the Debian root filesystem.

Paseo is licensed under Apache-2.0; see [LICENSE](LICENSE). Kai is Apache-2.0, PRoot is GPL-2.0, and talloc is LGPL-3.0. Details and sources are in [THIRD_PARTY_NOTICES.md](packages/app/modules/paseo-phone-runtime/THIRD_PARTY_NOTICES.md).
