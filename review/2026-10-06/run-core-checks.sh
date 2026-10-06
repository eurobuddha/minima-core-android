#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
CORE_REVIEW_ROOT=/Users/eurobuddha/Projects/minima/core/minima-core/merge-1.1.2.31
CORE_REVIEW_JAVA=/opt/homebrew/opt/openjdk@11/bin
CORE_REVIEW_H2=/Users/eurobuddha/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.1.214/d5c2005c9e3279201e12d4776c948578b16bf8b2/h2-2.1.214.jar
mkdir -p review/2026-10-06/classes
"$CORE_REVIEW_JAVA/javac" -cp app/libs/minima.jar -d review/2026-10-06/classes review/2026-10-06/CoreReviewCheck.java
"$CORE_REVIEW_JAVA/java" -Xmx256m -cp "review/2026-10-06/classes:app/libs/minima.jar:$CORE_REVIEW_H2:$CORE_REVIEW_ROOT/jar/minima.jar" CoreReviewCheck
"$CORE_REVIEW_JAVA/java" -cp "$CORE_REVIEW_ROOT/build/classes/java/test:app/libs/minima.jar:$CORE_REVIEW_H2" org.minima.database.txpowdb.sql.TxPoWSqlDBCleanCheck
