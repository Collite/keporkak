# CI checks the checked-in schemas; an explicit contracts path adds the verbatim comparison.
schema-check contracts="":
    #!/usr/bin/env sh
    set -eu
    if [ -n "{{contracts}}" ]; then
        build/python/bin/python build-support/check_schemas.py --contracts "{{contracts}}"
    else
        build/python/bin/python build-support/check_schemas.py
    fi
