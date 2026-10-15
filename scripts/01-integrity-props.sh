#!/system/bin/sh
# Early-boot property normalizer for Reisenless (post-fs-data stage)
# Location: /data/.xlmk/post-fs-data.d/01-integrity-props.sh

if [ -f "/data/.xlmk/fix_integrity.sh" ]; then
    sh "/data/.xlmk/fix_integrity.sh" </dev/null >/dev/null 2>&1
fi
