#!/usr/bin/env sh
# Build a separate consumer against isolated, locally published Maven artifacts.
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
smoke_dir=$(mktemp -d "${TMPDIR:-/tmp}/obs-consumer.XXXXXXXX")
trap 'rm -rf "$smoke_dir"' EXIT HUP INT TERM
cd "$project_root"
./mill --no-server '{protocol,core,sttp}.publishM2Local' --m2RepoPath "$smoke_dir/maven"
./mill --no-server show protocol.publishVersion > "$smoke_dir/version.json"
mkdir -p "$smoke_dir/consumer/src"
cat > "$smoke_dir/consumer/build.mill" <<'BUILD'
//| mill-version: 1.1.10
//| mill-jvm-index-version: 0.0.4-198-293bcc
//| mill-jvm-version: temurin:25.0.3
package build
import mill.*, scalalib.*
object `package` extends ScalaModule:
  def scalaVersion = "3.9.0"
  def mvnDeps = Seq(mvn"com.worxbend.obs.websocket.client::obs-websocket-client-sttp:CONSUMER_VERSION")
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
import com.worxbend.obs.websocket.client.transport.sttp.HandshakeHeaders
import com.worxbend.obs.websocket.client.transport.sttp.SttpObsClient
object Main:
  def main(args: Array[String]): Unit =
    assert(Runtime.version.feature == 25)
    assert(GetVersion().requestType == "GetVersion")
    val api = new RequestApi[String]:
      def request[A](value: Request[A]): Either[String, A] = Left(value.requestType)
    assert(api.general.getVersion() == Left("GetVersion"))
    assert(SceneRef.byName("Camera").map(_.setProgram.requestType) == Right("SetCurrentProgramScene"))
    assert(Screenshot.decode("data:image/png;base64,AQ==").map(_.bytes.size) == Right(1))
    assert(HandshakeHeaders.create(Vector("X-Client" -> "consumer-smoke")).isRight)
    assert(SttpObsClient.connect(ObsConfig(uri = "invalid"))(_ => ()).isLeft)
    println("Isolated published consumer compiled and ran on Java 25")
SCALA
cd "$smoke_dir/consumer"
COURSIER_REPOSITORIES="file://$smoke_dir/maven|https://repo.maven.apache.org/maven2" \
  "$project_root/mill" --no-server run
