"""Release gate and published-artifact verifier: pure checks, no network or Mill."""

import importlib.util
import io
import unittest
import urllib.error
from pathlib import Path
from tempfile import TemporaryDirectory


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


validate = load("validate_release")
published = load("verify_published")
SEVEN = "\n".join(f"{m}.publishArtifacts" for m in validate.LIBRARIES)


class ValidateReleaseTests(unittest.TestCase):
    def test_tag_gives_stable_version(self):
        self.assertEqual(validate.release_version("tag", "v0.3.0"), "0.3.0")

    def test_branches_and_non_stable_tags_rejected(self):
        for ref_type, tag in (("branch", "v0.3.0"), ("tag", "0.3.0"), ("tag", "v0.3.0-RC1")):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate.release_version(ref_type, tag)

    def test_exactly_seven_libraries_publish(self):
        validate.check_modules(SEVEN)
        for resolved in (SEVEN + "\nserver.publishArtifacts", "core.publishArtifacts"):
            with self.subTest(resolved=resolved), self.assertRaises(ValueError):
                validate.check_modules(resolved)

    def test_all_versions_match(self):
        versions = dict.fromkeys(validate.LIBRARIES, "0.3.0")
        validate.check_versions("0.3.0", versions)
        with self.assertRaises(ValueError):
            validate.check_versions("0.3.0", versions | {"zio": "0.3.0-SNAPSHOT"})
        with self.assertRaises(ValueError):
            validate.check_versions("0.3.0", {"core": "0.3.0"})

    def test_changelog_heading_required(self):
        validate.check_changelog("0.3.0", "# Changelog\n\n## 0.3.0 — 2026-10-24\n")
        for text in ("## Unreleased\n", "## 0.3.01 — x\n", "- 0.3.0\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                validate.check_changelog("0.3.0", text)


def publish_data(module, version, local="/out/x.jar"):
    artifact = f"obs-websocket-client-{module}_3"
    return {
        "meta": {"group": published.GROUP, "id": artifact, "version": version},
        "payload": [
            [f"ref:v0:abc:{local}", f"{artifact}-{version}{s}"] for s in published.SUFFIXES
        ],
    }


class Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


class VerifyPublishedTests(unittest.TestCase):
    def test_artifact_paths_follow_maven_layout(self):
        files = published.artifact_files(publish_data("zio", "0.3.0"), "zio", "0.3.0")
        self.assertEqual(files[0][0], Path("/out/x.jar"))
        self.assertEqual(
            files[0][1],
            "com/worxbend/obs-websocket-client-zio_3/0.3.0/obs-websocket-client-zio_3-0.3.0.pom",
        )

    def test_wrong_coordinates_or_payload_rejected(self):
        data = publish_data("zio", "0.3.0")
        with self.assertRaises(ValueError):
            published.artifact_files(data, "zio", "0.3.1")
        data["payload"] = data["payload"][:3]
        with self.assertRaises(ValueError):
            published.artifact_files(data, "zio", "0.3.0")

    def test_headers_only_for_github_packages(self):
        self.assertEqual(published.request_headers(published.CENTRAL, {}), {})
        env = {"GITHUB_PACKAGES_USERNAME": "u", "GITHUB_PACKAGES_PASSWORD": "p"}
        headers = published.request_headers("https://maven.pkg.github.com/o/r", env)
        self.assertEqual(headers, {"Authorization": "Basic dTpw"})
        with self.assertRaises(ValueError):
            published.request_headers("https://example.com/maven", env)

    def run_verify(self, local_bytes, replies):
        sleeps: list[float] = []
        calls = iter(replies)

        def open_url(request, timeout):
            reply = next(calls)
            if isinstance(reply, Exception):
                raise reply
            return Response(reply)

        with TemporaryDirectory() as directory:
            local = Path(directory) / "a.jar"
            local.write_bytes(local_bytes)
            published.verify(
                [(local, "a.jar")],
                published.CENTRAL,
                {},
                open_url=open_url,
                clock=lambda: 0.0,
                sleep=sleeps.append,
            )
        return sleeps

    def test_matching_file_passes_after_propagation_retries(self):
        missing = urllib.error.HTTPError("u", 404, "missing", {}, None)  # type: ignore[arg-type]
        offline = urllib.error.URLError("down")
        self.assertEqual(self.run_verify(b"jar", [missing, offline, b"jar"]), [5, 5])

    def test_differing_file_fails(self):
        with self.assertRaises(ValueError):
            self.run_verify(b"jar", [b"other"])

    def test_non_retried_error_and_deadline_fail(self):
        forbidden = urllib.error.HTTPError("u", 403, "no", {}, None)  # type: ignore[arg-type]
        with self.assertRaises(urllib.error.HTTPError):
            self.run_verify(b"jar", [forbidden])
        with TemporaryDirectory() as directory:
            local = Path(directory) / "a.jar"
            local.write_bytes(b"jar")
            ticks = iter([0.0, 2000.0])
            missing = urllib.error.HTTPError("u", 404, "missing", {}, None)  # type: ignore[arg-type]

            def open_url(request, timeout):
                raise missing

            with self.assertRaises(urllib.error.HTTPError):
                published.verify(
                    [(local, "a.jar")],
                    published.CENTRAL,
                    {},
                    open_url=open_url,
                    clock=lambda: next(ticks),
                    sleep=lambda _: None,
                )


if __name__ == "__main__":
    unittest.main()
