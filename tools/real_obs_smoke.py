#!/usr/bin/env python3
"""Run the library against an authenticated, source-free OBS in an isolated container.

Requires Docker and network access for Debian packages. OBS_DOCKER_IMAGE can
select a Debian-compatible base; no host directories are mounted in any case.
This does not use the installed OBS application or its configuration.
"""
import base64
import datetime
import json
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import subprocess
import sys
import time

from check_coverage import source_digest

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'out/real-obs-smoke'


def run(args, **kwargs):
    return subprocess.run(args, check=True, timeout=kwargs.pop('timeout', 60), **kwargs)


def command(name, *args, **kwargs):
    interactive = ['-i'] if 'input' in kwargs else []
    return run(['docker', 'exec', *interactive, name, *args], **kwargs)


def write_config(name, relative, text):
    command(name, 'sh', '-c', 'mkdir -p "$(dirname "$1")"\ncat > "$1"', 'sh',
            '/tmp/obs-disposable/' + relative, input=text, text=True)


def start_obs(name):
    run(['docker', 'exec', '-d', '-e', 'DISPLAY=:99',
         '-e', 'XDG_CONFIG_HOME=/tmp/obs-disposable/config',
         '-e', 'XDG_CACHE_HOME=/tmp/obs-disposable/cache',
         '-e', 'XDG_DATA_HOME=/tmp/obs-disposable/data',
         '-e', 'XDG_RUNTIME_DIR=/tmp/obs-disposable/runtime',
         '-e', 'LIBGL_ALWAYS_SOFTWARE=1', '-e', 'QT_QPA_PLATFORM=xcb', name,
         'sh', '-c', 'printf "%s\\n" "$$" > /tmp/obs-disposable/obs.pid\n'
         'exec obs --multi --only-bundled-plugins --disable-missing-files-check '
         '--disable-shutdown-check --websocket_ipv4_only >/tmp/obs-disposable/obs.log 2>&1'])


def wait_ready(port):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=1) as peer:
                key = base64.b64encode(secrets.token_bytes(16)).decode()
                peer.sendall(('GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n'
                              'Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n'
                              f'Sec-WebSocket-Key: {key}\r\n\r\n').encode())
                if peer.recv(1024).startswith(b'HTTP/1.1 101'):
                    return
        except OSError:
            pass
        time.sleep(.25)
    raise RuntimeError('Disposable OBS did not become ready within 30 seconds')


def interrupted(number, _frame):
    raise SystemExit(128 + number)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'evidence.json').unlink(missing_ok=True)
    name = 'obs-client-smoke-' + secrets.token_hex(6)
    password = secrets.token_urlsafe(32)
    image = os.environ.get('OBS_DOCKER_IMAGE', 'debian:13-slim')
    evidence = None

    signal.signal(signal.SIGTERM, interrupted)
    try:
        run(['docker', 'run', '--rm', '--init', '-d', '--name', name, '--user', '0',
             '--publish', '127.0.0.1::4455', '--security-opt', 'no-new-privileges',
             '--memory', '2g', '--pids-limit', '512',
             '--label', 'com.worxbend.obs-client.disposable=true', image, 'sleep', '1800'],
            stdout=subprocess.DEVNULL, timeout=180)
        inspected = json.loads(run(['docker', 'inspect', name], capture_output=True, text=True).stdout)[0]
        if inspected['Mounts']:
            raise RuntimeError('Refusing integration: container unexpectedly has filesystem mounts')
        bindings = inspected['NetworkSettings']['Ports']['4455/tcp']
        if len(bindings) != 1 or bindings[0]['HostIp'] != '127.0.0.1':
            raise RuntimeError('Refusing integration: container port is not exclusively bound to IPv4 loopback')
        port = int(bindings[0]['HostPort'])
        print('Installing real OBS and private Xvfb in a mount-free disposable container...', flush=True)
        with (OUT / 'install.log').open('w') as log:
            command(name, 'sh', '-ec', 'apt-get update -qq\n'
                    'DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends '
                    'obs-studio xvfb libgl1-mesa-dri', stdout=log, stderr=subprocess.STDOUT, timeout=300)
        write_config(name, 'config/obs-studio/plugin_config/obs-websocket/config.json', json.dumps({
            'server_enabled': True, 'server_port': 4455, 'auth_required': True,
            'server_password': password, 'alerts_enabled': False, 'first_load': False}))
        write_config(name, 'config/obs-studio/global.ini', '[General]\nFirstRun=false\n[BasicWindow]\nPreviewEnabled=false\n')
        write_config(name, 'runtime/.keep', '')
        command(name, 'chmod', '700', '/tmp/obs-disposable/runtime')
        run(['docker', 'exec', '-d', name, 'Xvfb', ':99', '-screen', '0', '1280x720x24', '-nolisten', 'tcp', '-ac'])
        for _ in range(40):
            ready = subprocess.run(['docker', 'exec', name, 'test', '-S', '/tmp/.X11-unix/X99'],
                                   timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            time.sleep(.1)
        else:
            raise RuntimeError('Private Xvfb did not become ready')
        start_obs(name)
        test_env = os.environ.copy()
        test_env.update(OBS_INTEGRATION_DISPOSABLE='true', OBS_INTEGRATION_SCENE_MUTATIONS='true',
                        OBS_WS_URL=f'ws://127.0.0.1:{port}', OBS_WS_PASSWORD=password)
        for phase in ('before-restart', 'after-restart'):
            wait_ready(port)
            print(f'Running authenticated discovery and scene/event checks: {phase}', flush=True)
            with (OUT / f'{phase}.log').open('w') as log:
                run([str(ROOT / 'mill'), '--no-server', 'integration.test'], cwd=ROOT,
                    env=test_env, stdout=log, stderr=subprocess.STDOUT, timeout=600)
            phase_log = (OUT / f'{phase}.log').read_text()
            summary = re.search(r'(\d+) failed, (\d+) ignored, (\d+) total', phase_log)
            markers = ('Verified incorrect-password authentication rejection with close code 4009',
                       'Verified read-only OBS ',
                       'Verified Reidentify acknowledgements preserve the live OBS session',
                       'Verified disposable scene creation, switching, event delivery, restoration and removal')
            if (summary is None or int(summary[1]) != 0 or int(summary[2]) != 0 or int(summary[3]) < 4
                    or any(marker not in phase_log for marker in markers)):
                raise RuntimeError(f'{phase}: required non-skipped integration checks were not reported')
            if phase == 'before-restart':
                command(name, 'sh', '-c', 'kill -TERM "$(cat /tmp/obs-disposable/obs.pid)"')
                for _ in range(100):
                    stopped = subprocess.run(['docker', 'exec', name, 'sh', '-c',
                                              'kill -0 "$(cat /tmp/obs-disposable/obs.pid)"'], timeout=10,
                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    if stopped.returncode != 0:
                        break
                    time.sleep(.1)
                else:
                    raise RuntimeError('Disposable OBS did not stop before restart')
                start_obs(name)
        version = command(name, 'dpkg-query', '-W', '-f=${Version}', 'obs-studio', capture_output=True, text=True).stdout
        evidence = {
            'completed_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'source_sha256': source_digest(ROOT),
            'base_image': image, 'base_image_id': inspected['Image'], 'obs_debian_package': version,
            'host_mounts': [], 'host_bind': '127.0.0.1',
            'phases': ['authenticated discovery and scene/event checks', 'stop and restart',
                       'authenticated discovery and scene/event checks after restart'],
            'streaming_started': False, 'recording_started': False,
            'limitation': 'Restart smoke uses fresh scoped connections; reconnect-loop and in-flight race behavior are covered by scripted tests.'
        }
    finally:
        # The unique task-owned name is never an existing user container.
        try:
            subprocess.run(['docker', 'cp', name + ':/tmp/obs-disposable/obs.log', str(OUT / 'obs.log')],
                           timeout=20, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        finally:
            removed = subprocess.run(['docker', 'rm', '--force', name], timeout=30,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
            if removed.returncode != 0:
                message = f'Could not remove owned disposable container {name}: {removed.stderr.strip()}'
                if sys.exc_info()[0] is not None:
                    print(message, file=sys.stderr)
                else:
                    raise RuntimeError(message)
    if evidence is not None:
        evidence['container_removed'] = True
        (OUT / 'evidence.json').write_text(json.dumps(evidence, indent=2))
        print(f'Real OBS {version} smoke passed before and after restart. Evidence: {OUT}', flush=True)


if __name__ == '__main__':
    main()
