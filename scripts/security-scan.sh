#!/usr/bin/env bash
# Reproducible offline inventory over the aggregate SBOM and actual consumer JAR closures.
#
# Usage:
#   TRIVY_CACHE_DIR=/path/to/validated-cache scripts/security-scan.sh all
#   TRIVY_CACHE_DIR=/path/to/validated-cache scripts/security-scan.sh sbom
#   TRIVY_CACHE_DIR=/path/to/validated-cache scripts/security-scan.sh rootfs
#   TRIVY_CACHE_DIR=/path/to/validated-cache scripts/security-scan.sh secrets
#
# Run `scripts/published-consumer-gate.py` first. This script never downloads a vulnerability
# database and never substitutes `trivy fs` for dependency scanning.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-all}"
OUT="${OUT_DIR:-$ROOT/target/security}"
MATRIX="$ROOT/target/published-consumer-gate/matrix.json"

if [[ -z "${TRIVY_CACHE_DIR:-}" ]]; then
	echo "error: TRIVY_CACHE_DIR must point to a validated, frozen Trivy cache" >&2
	exit 2
fi
for database in \
	"$TRIVY_CACHE_DIR/db/trivy.db" \
	"$TRIVY_CACHE_DIR/db/metadata.json" \
	"$TRIVY_CACHE_DIR/java-db/trivy-java.db" \
	"$TRIVY_CACHE_DIR/java-db/metadata.json"; do
	[[ -f "$database" ]] || { echo "error: missing $database" >&2; exit 2; }
done
command -v trivy >/dev/null || { echo "error: trivy is not on PATH" >&2; exit 2; }
command -v jq >/dev/null || { echo "error: jq is not on PATH" >&2; exit 2; }
mkdir -p "$OUT/rootfs"

trivy_offline() {
	trivy --cache-dir "$TRIVY_CACHE_DIR" "$@" \
		--skip-db-update --skip-java-db-update --offline-scan \
		--disable-telemetry --skip-version-check
}

scan_sbom() {
	local version bom
	version=$("$ROOT/mvnw" -q -N help:evaluate -Dexpression=project.version -DforceStdout)
	bom="$ROOT/target/agent-client-parent-$version-cyclonedx.json"
	[[ -f "$bom" ]] || {
		echo "error: aggregate SBOM missing: $bom (run a release-profile verify/install first)" >&2
		exit 2
	}
	trivy_offline sbom --scanners vuln --format json \
		--output "$OUT/trivy-sbom-vulnerabilities.json" "$bom"
	echo "aggregate SBOM findings: $(jq '[.Results[]?.Vulnerabilities[]?] | length' "$OUT/trivy-sbom-vulnerabilities.json")"
}

scan_rootfs() {
	[[ -f "$MATRIX" ]] || {
		echo "error: consumer matrix missing: $MATRIX (run scripts/published-consumer-gate.py first)" >&2
		exit 2
	}
	while IFS= read -r module; do
		local closure="$ROOT/target/published-consumer-gate/consumers/$module/closure"
		[[ -d "$closure" ]] || { echo "error: consumer closure missing: $closure" >&2; exit 2; }
		trivy_offline rootfs --scanners vuln --format json \
			--output "$OUT/rootfs/$module.json" "$closure"
		echo "$module findings: $(jq '[.Results[]?.Vulnerabilities[]?] | length' "$OUT/rootfs/$module.json")"
	done < <(jq -r '.consumers[].module' "$MATRIX")
}

scan_secrets() {
	trivy_offline fs --scanners secret --skip-dirs target --skip-dirs .git \
		--format json --output "$OUT/trivy-secret-scan.json" "$ROOT"
	echo "secret findings: $(jq '[.Results[]?.Secrets[]?] | length' "$OUT/trivy-secret-scan.json")"
}

case "$MODE" in
	all)
		scan_sbom
		scan_rootfs
		;;
	sbom) scan_sbom ;;
	rootfs) scan_rootfs ;;
	secrets) scan_secrets ;;
	*) echo "usage: $0 [all|sbom|rootfs|secrets]" >&2; exit 2 ;;
esac

echo "wrote offline scan results under $OUT"
