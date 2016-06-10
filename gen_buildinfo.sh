#!/bin/bash

dest=$1
package=$2
version=$3

cat > $dest <<EOF
package $package;
public class BuildInfo
{
    public final static String COMMIT="$(git rev-parse HEAD)";
    public final static String VERSION="$version";
}
EOF
