#!/usr/bin/env sh
# Build a separate consumer against the seven published artifacts.
#   consumer_smoke.sh                     publish to isolated temp Maven repo, resolve from it
#   consumer_smoke.sh VERSION REPOSITORY  resolve VERSION from REPOSITORY with a fresh Coursier
#                                         cache, no local fallback (release jobs; GitHub Packages
#                                         also needs COURSIER_CREDENTIALS)
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
smoke_dir=$(mktemp -d "${TMPDIR:-/tmp}/obs-consumer.XXXXXXXX")
trap 'rm -rf "$smoke_dir"' EXIT HUP INT TERM
central=https://repo.maven.apache.org/maven2
cd "$project_root"
if [ "$#" -eq 2 ]; then
  printf '"%s"\n' "$1" > "$smoke_dir/version.json"
  repositories="$2"
  [ "$2" = "$central" ] || repositories="$2|$central"
  export COURSIER_CACHE="$smoke_dir/coursier"
elif [ "$#" -eq 0 ]; then
  ./mill --no-server '{protocol,core,sttp,okhttp,zio,fs2,pekko}.publishM2Local' --m2RepoPath "$smoke_dir/maven"
  ./mill --no-server show protocol.publishVersion > "$smoke_dir/version.json"
  repositories="file://$smoke_dir/maven|$central"
else
  echo "usage: $0 [VERSION REPOSITORY]" >&2
  exit 2
fi
mkdir -p "$smoke_dir/consumer/src"
cat > "$smoke_dir/consumer/build.mill" <<'BUILD'
//| mill-version: 1.1.10
//| mill-jvm-index-version: 0.0.4-198-293bcc
//| mill-jvm-version: temurin:25.0.3
package build
import mill.*, scalalib.*
object `package` extends ScalaModule:
  def scalaVersion = "3.9.0"
  def mvnDeps = Seq(
    mvn"com.worxbend::obs-websocket-client-sttp:CONSUMER_VERSION",
    mvn"com.worxbend::obs-websocket-client-okhttp:CONSUMER_VERSION",
    mvn"com.worxbend::obs-websocket-client-zio:CONSUMER_VERSION",
    mvn"com.worxbend::obs-websocket-client-fs2:CONSUMER_VERSION",
    mvn"com.worxbend::obs-websocket-client-pekko:CONSUMER_VERSION",
  )
BUILD
python3 - "$smoke_dir/version.json" "$smoke_dir/consumer/build.mill" <<'PYTHON'
import json
from pathlib import Path
import sys
version = json.loads(Path(sys.argv[1]).read_text())
build = Path(sys.argv[2])
build.write_text(build.read_text().replace("CONSUMER_VERSION", version))
PYTHON
cat > "$smoke_dir/consumer/src/Main.scala" <<'SCALA'
import com.worxbend.obs.websocket.client.ObsConfig
import com.worxbend.obs.websocket.client.protocol.requests.GetVersion
import com.worxbend.obs.websocket.client.protocol.{Request, RequestApi, SceneRef}
import com.worxbend.obs.websocket.client.protocol.workflows.Screenshot
import com.worxbend.obs.websocket.client.transport.HandshakeHeaders
import com.worxbend.obs.websocket.client.transport.fs2.{Fs2ObsClient, Fs2Options}
import com.worxbend.obs.websocket.client.transport.okhttp.{OkHttpClientOptions, OkHttpObsClient}
import com.worxbend.obs.websocket.client.transport.pekko.{PekkoObsClient, PekkoOptions}
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient
import com.worxbend.obs.websocket.client.transport.zio.{ZioObsClient, ZioOptions}
object Main:
  def main(args: Array[String]): Unit =
    assert(Runtime.version.feature == 25)
    assert(GetVersion().requestType == "GetVersion")
    assert(summon[com.worxbend.obs.websocket.client.BatchCodec[EmptyTuple]] != null)
    // Tuple evidence is public; unchecked internal projection is deliberately not consumer-callable.
    assert(!scala.compiletime.testing.typeChecks("""summon[com.worxbend.obs.websocket.client.BatchCodec[EmptyTuple]].decode(EmptyTuple, Vector.empty)"""))
    val api = new RequestApi[String]:
      def request[A](value: Request[A]): Either[String, A] = Left(value.requestType)
    assert(api.general.getVersion() == Left("GetVersion"))
    assert(SceneRef.byName("Camera").map(_.setProgram().requestType) == Right("SetCurrentProgramScene"))
    assert(Screenshot.decode("data:image/png;base64,AQ==").map(_.bytes.size) == Right(1))
    assert(HandshakeHeaders.create(Vector("X-Client" -> "consumer-smoke")).isRight)
    // Configuration is validated before any backend client is built, so each entrypoint
    // proves its artifact resolves and runs without needing a live OBS peer.
    val invalid = ObsConfig(uri = "invalid")
    assert(SttpObsClient.connect(invalid)(_ => ()).isLeft)
    assert(OkHttpObsClient.connect(invalid)(_ => ()).isLeft)
    assert(ZioObsClient.connect(invalid)(_ => ()).isLeft)
    assert(Fs2ObsClient.connect(invalid)(_ => ()).isLeft)
    assert(PekkoObsClient.connect(invalid)(_ => ()).isLeft)
    // A real connect to a guaranteed-closed port initializes each runtime bridge
    // (JDK client, OkHttp dispatcher, ZIO Runtime, IORuntime + Dispatcher, ActorSystem)
    // on the isolated-repo classpath and fails fast.
    val closed = ObsConfig(uri = "ws://127.0.0.1:1", connectionTimeout = scala.concurrent.duration.Duration(2, "s"))
    assert(SttpObsClient.connect(closed)(_ => ()).isLeft)
    assert(OkHttpObsClient.connect(closed)(_ => ()).isLeft)
    assert(ZioObsClient.connect(closed)(_ => ()).isLeft)
    assert(Fs2ObsClient.connect(closed)(_ => ()).isLeft)
    assert(PekkoObsClient.connect(closed)(_ => ()).isLeft)
    assert(OkHttpClientOptions().validate.isRight)
    assert(ZioOptions().validate.isRight)
    assert(Fs2Options().validate.isRight)
    assert(PekkoOptions().validate.isRight)
    println("Isolated published consumer compiled and ran all seven artifacts on Java 25")
SCALA
cd "$smoke_dir/consumer"
COURSIER_REPOSITORIES="$repositories" "$project_root/mill" --no-server run
