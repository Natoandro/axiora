#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf "Usage: %s [--font FONT_FILE] [--] 'LATEX_EXPRESSION' ['LATEX_EXPRESSION' ...]\n" "$0"
}

if [[ "${1:-}" == --help ]]; then
    usage
    exit 0
fi

font=
if [[ "${1:-}" == --font ]]; then
    if (( $# < 2 )) || [[ -z "$2" ]]; then
        usage >&2
        exit 2
    fi
    font=$2
    shift 2
fi
if [[ "${1:-}" == -- ]]; then
    shift
fi
if (( $# == 0 )); then
    usage >&2
    exit 2
fi

if [[ -z "$font" ]]; then
    if command -v fc-match >/dev/null && \
        match=$(fc-match -f '%{family[0]}\n%{file}' 'Latin Modern Math') && \
        [[ "$match" == "Latin Modern Math"$'\n'* ]]; then
        font=${match#*$'\n'}
    else
        echo "Could not find Latin Modern Math. Install it or pass --font FONT_FILE." >&2
        exit 2
    fi
fi
if [[ ! -f "$font" || ! -r "$font" ]]; then
    printf 'Cannot read font file: %s\n' "$font" >&2
    exit 2
fi

[[ "$font" = /* ]] || font="$PWD/$font"
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$repo_root"
output_root="$repo_root/texlet-previews"
mkdir -p -- "$output_root"
cargo=cargo
if ! command -v cargo >/dev/null && [[ -x "$HOME/.cargo/bin/cargo" ]]; then
    cargo="$HOME/.cargo/bin/cargo"
fi

index=0
status=0
for expression in "$@"; do
    index=$((index + 1))
    if svg=$("$cargo" run --quiet --locked -p texlet-svg --example preview -- \
        "$font" "$expression"); then
        printf -v prefix 'preview-%03d' "$index"
        output=$(mktemp "$output_root/$prefix-XXXXXXXX.svg")
        printf '%s\n' "$svg" > "$output"
        printf '%s\n' "$output"
    else
        printf 'Could not render expression %d.\n' "$index" >&2
        status=1
    fi
done
exit "$status"
