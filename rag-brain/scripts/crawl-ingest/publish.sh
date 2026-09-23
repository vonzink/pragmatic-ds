#!/usr/bin/env bash
# Stage crawl-ingest output into the S3 corpus, then deep-merge its partial manifest
# into the live _manifest.json so existing corpus entries are preserved.
#
# DRY-RUN BY DEFAULT — prints what it would do and changes nothing. Pass --apply to write.
# After --apply, run the existing ingester:  cd ../s3-ingest && node sync.mjs --dry-run
#
# Requires: aws CLI (default cred chain) and jq. Targets the same bucket/prefix as
# the configured S3 bucket unless overridden.
set -euo pipefail

BUCKET="${S3_BUCKET:-}"
PREFIX="${S3_PREFIX:-rag-brain/}"
OUT="${OUT:-$(cd "$(dirname "$0")" && pwd)/out}"
APPLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --bucket) BUCKET="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --out)    OUT="$2"; shift 2 ;;
    --apply)  APPLY=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

command -v aws >/dev/null || { echo "ERROR: aws CLI not found"; exit 1; }
command -v jq  >/dev/null || { echo "ERROR: jq not found"; exit 1; }
[ -f "$OUT/manifest.partial.json" ] || { echo "ERROR: $OUT/manifest.partial.json not found — run crawl.py first"; exit 1; }

MANIFEST_KEY="s3://${BUCKET}/${PREFIX}_manifest.json"
mds=$(find "$OUT" -maxdepth 1 -name '*.md' | sort)
[ -n "$mds" ] || { echo "no *.md files in $OUT"; exit 1; }

echo "bucket=$BUCKET prefix=$PREFIX out=$OUT  $([ $APPLY -eq 1 ] && echo '(APPLY)' || echo '(DRY RUN)')"
echo "files to stage:"; echo "$mds" | sed "s|$OUT/|  |"

# Merge: live _manifest.json  *  {files: partial.files}. jq '*' is a recursive object
# merge, so new file entries are added and existing ones preserved (same key overwritten).
live=$(aws s3 cp "$MANIFEST_KEY" - 2>/dev/null || echo '{"defaults":{},"files":{}}')
merged=$(jq -s '.[0] * .[1]' <(printf '%s' "$live") "$OUT/manifest.partial.json")
added=$(jq -r '.files | keys[]' "$OUT/manifest.partial.json" | sort)
echo "manifest entries to merge:"; echo "$added" | sed 's/^/  /'

if [ $APPLY -eq 0 ]; then
  echo; echo "DRY RUN — nothing written. Re-run with --apply to publish, then run ../s3-ingest/sync.mjs."
  exit 0
fi

while IFS= read -r f; do
  [ -n "$f" ] || continue
  aws s3 cp "$f" "s3://${BUCKET}/${PREFIX}$(basename "$f")"
done <<< "$mds"

printf '%s' "$merged" | aws s3 cp - "$MANIFEST_KEY" --content-type application/json
echo "published. now ingest:  cd ../s3-ingest && node sync.mjs --dry-run   (then without --dry-run)"
