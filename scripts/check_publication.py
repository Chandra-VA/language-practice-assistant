"""Best-effort publication checks on the Git index; never print matched secrets."""
import hashlib
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALLOWED_DIRS = {"app", "docs", "gradle", "scripts", "third_party", ".github"}
ALLOWED_ROOT = {".gitignore", ".gitattributes", ".editorconfig", "README.md", "LICENSE",
                "SECURITY.md", "PRIVACY.md", "DISCLAIMER.md", "CONTRIBUTING.md",
                "THIRD_PARTY_NOTICES.md", "CHANGELOG.md", "build.gradle.kts",
                "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"}
BLOCKED_PARTS = {"build", ".gradle", ".tools", ".analysis", ".signing", ".aws", ".ssh", ".codex", ".agents"}
BLOCKED_NAMES = {"local.properties", "signing.properties", "user_vocabulary.csv",
                 "learned_vocabulary.tsv", "manual_vocabulary.tsv", "ai_vocabulary.tsv", "google-services.json"}
BLOCKED_SUFFIXES = {".apk", ".aab", ".p12", ".pfx", ".jks", ".keystore", ".key", ".pem",
                    ".mp4", ".mov", ".webm", ".zip", ".log", ".db", ".sqlite", ".hprof"}
SECRET = re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|"
                    rb"sk-[A-Za-z0-9_-]{24,}|AIza[0-9A-Za-z_-]{35}|AKIA[0-9A-Z]{16}|"
                    rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)")
LOCAL_PATH = re.compile(rb"(?:[A-Za-z]:[\\/]Users[\\/]|/Users/|/home/)[A-Za-z0-9_.-]+")
PRIVATE_MEMORY_PREFIXES = ("verified_sentences.json", "verified_exercises.json")
WRAPPER_SHA = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"


def main():
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    paths = [p for p in paths if p]
    if not paths:
        raise SystemExit("No indexed files; stage only reviewed source files first.")
    failures = []
    for path in paths:
        item = pathlib.PurePosixPath(path)
        reasons = []
        if item.parts[0] not in ALLOWED_DIRS and path not in ALLOWED_ROOT:
            reasons.append("outside publication allowlist")
        if set(item.parts) & BLOCKED_PARTS or item.name in BLOCKED_NAMES or item.suffix.lower() in BLOCKED_SUFFIXES:
            reasons.append("local/private/generated file")
        if item.name.startswith(PRIVATE_MEMORY_PREFIXES):
            reasons.append("private answer memory")
        if item.name.startswith(".env") or item.name.startswith("service-account"):
            reasons.append("credential configuration")
        mode = subprocess.check_output(["git", "ls-files", "-s", "--", path], cwd=ROOT).split()[0]
        if mode == b"120000":
            reasons.append("symlink requires review")
        data = subprocess.check_output(["git", "show", ":" + path], cwd=ROOT)
        if len(data) > 2 * 1024 * 1024:
            reasons.append("unexpected large file")
        if SECRET.search(data):
            reasons.append("possible credential")
        if item.suffix != ".jar" and LOCAL_PATH.search(data):
            reasons.append("personal absolute path")
        if item.suffix == ".jar" and (path != "gradle/wrapper/gradle-wrapper.jar"
                or hashlib.sha256(data).hexdigest() != WRAPPER_SHA):
            reasons.append("unverified binary")
        if reasons:
            failures.append(path + ": " + ", ".join(reasons))
    if failures:
        print("Publication check FAILED (contents and secrets redacted):")
        print("\n".join(failures))
        return 1
    print(f"Publication check passed: {len(paths)} indexed files; no configured rule matched.")
    print("This is not proof that the source has no vulnerabilities or sensitive data.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
