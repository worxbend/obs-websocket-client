# Local OBS isolation incident

On 2026-10-04, an attempted headless integration check launched the installed
OBS Flatpak with a temporary `XDG_CONFIG_HOME` supplied through `flatpak run
--env`. Flatpak replaced that reserved environment variable with its normal
application configuration directory. The temporary configuration was therefore
not used. This was an isolation failure; this attempt is not successful
integration evidence.

The additional process group, PID **572529**, was stopped with `SIGKILL` after
the failure was detected, to avoid an ordinary shutdown saving further state.
The pre-existing OBS Flatpak instance, PID **491399**, remained running. No
further OBS Flatpak launches were performed. No configuration restoration or
overwrite was attempted.

The attempted instance used Qt's offscreen platform. Host filesystem access,
audio sockets, X11/Wayland sockets, and device access were restricted, but those
restrictions did not hide Flatpak's own application configuration directory.
The OBS startup log showed attempted loading of existing source configuration;
the denied media/audio access produced errors. No streaming or recording was
started by the integration commands.

A metadata-only audit identified the following files with modification times
in the attempt's startup interval. Paths are relative to
`~/.var/app/com.obsproject.Studio/config/obs-studio/`; file contents were not read
for this audit. Because the original OBS instance was running concurrently,
the modification times alone do not prove which process caused each update or
whether the contents changed.

| Relative path | Local modification time (Europe/Kyiv) |
| --- | --- |
| `user.ini` | `2026-10-04T01:04:42.539987` |
| `logs/2026-10-04 01-04-42.txt` | `2026-10-04T01:04:43.557989` |
| `plugin_manager/modules.json` | `2026-10-04T01:04:43.453988` |
| `updates/whatsnew.json` | `2026-10-04T01:04:43.687990` |
| `basic/profiles/Untitled/basic.ini` | `2026-10-04T01:04:42.538982` |
| `plugin_config/obs-websocket/config.json` | `2026-10-04T01:04:43.436988` |

Subsequent integration work uses a disposable Docker container with no host
filesystem mounts and a WebSocket port published only on `127.0.0.1`. It does
not reuse the installed Flatpak or the user's OBS configuration.
