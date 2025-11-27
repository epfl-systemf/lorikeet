#!/bin/bash

ROOT="$(pwd)"

# --- CONFIG ---
# Directory of the lab project with build.sbt and scalafix config
LAB_DIR="$ROOT/find"
# Directory containing student submissions
SUBMISSIONS_DIR="$ROOT/submissions"
# Submitted file to check
TARGET_FILE="find.scala"
# Path within the lab project where the submitted file should be placed
TARGET_PATH="src/main/scala/find"
# Log file to store results
LOG_FILE="$ROOT/grading_log_$(date +%Y.%m.%d_%H.%M.%S).txt"
# Directory to store the diffs between original and refactored code
DIFF_DIR="$ROOT/grading_diffs_$(date +%Y.%m.%d_%H.%M.%S)"
# Directory to store clean, structured lint reports
LINT_DIR="$ROOT/grading_reports_$(date +%Y.%m.%d_%H.%M.%S)"
# Directory to store temporary files
TMP_DIR="$ROOT/tmp"
# ---------------------

mkdir -p "$DIFF_DIR"
mkdir -p "$LINT_DIR"
mkdir -p "$TMP_DIR"

echo "Starting automated checks. Log file: $LOG_FILE"
echo "Refactoring diffs will be saved in: $DIFF_DIR"
echo "Lint reports will be saved in: $LINT_DIR"
echo "------------------------------------------------------" >> "$LOG_FILE"

# --- SBT SERVER ---
echo "Starting persistent sbt server in $LAB_DIR..."
(cd "$LAB_DIR" && sbt -Dsbt.server.forcestart=true "compile") & # launch sbt server in background
SBT_PID=$!
trap "echo 'Stopping sbt server...'; kill $SBT_PID 2>/dev/null; rm -rf '$TMP_DIR'" EXIT
sleep 10 # time to start the server

MISSING_FILE_SUBMISSIONS=0
COMPILE_ERROR_SUBMISSIONS=0
RULE_MATCH_SUBMISSIONS=0
TOTAL_SUBMISSIONS=0

for STUDENT_DIR in "$SUBMISSIONS_DIR"/*/; do
    STUDENT_ID=$(basename "$STUDENT_DIR")

    LATEST_ATTEMPT_DIR=$(find "$STUDENT_DIR" -maxdepth 1 -type d -regextype egrep -regex "$STUDENT_DIR[0-9]+$" | sort -V | tail -n 1)
    SUBMISSION_FILE="$LATEST_ATTEMPT_DIR/$TARGET_FILE"

    SUBMISSION_ID="$STUDENT_ID / $(basename "$LATEST_ATTEMPT_DIR")"
    
    if [ ! -f "$SUBMISSION_FILE" ]; then
        echo "   -> ❓ MISSING: $SUBMISSION_ID" | tee -a "$LOG_FILE"
        let MISSING_FILE_SUBMISSIONS++
        continue
    fi

    let TOTAL_SUBMISSIONS++

    TARGET_FILE_PATH="$TARGET_PATH/$TARGET_FILE"
    
    TMP_ORIGINAL="$TMP_DIR/$STUDENT_ID.$TARGET_FILE.original"
    TMP_REFACTORED="$TMP_DIR/$STUDENT_ID.$TARGET_FILE.refactored"
    DIFF_OUTPUT_FILE="$DIFF_DIR/$STUDENT_ID-$(basename "$LATEST_ATTEMPT_DIR").diff"
    
    TMP_RAW_LINT_OUTPUT="$TMP_DIR/$STUDENT_ID.$TARGET_FILE.raw_lint"
    LINT_REPORT_FILE="$LINT_DIR/$STUDENT_ID-$(basename "$LATEST_ATTEMPT_DIR").lint.txt"

    cp "$SUBMISSION_FILE" "$LAB_DIR/$TARGET_PATH/"
    pushd "$LAB_DIR" > /dev/null

    sbt --client -Dsbt.log.noformat=true "scalafmt" >> "$LOG_FILE" 2>&1
    cp "$TARGET_FILE_PATH" "$TMP_ORIGINAL"

    sbt --client -Dsbt.log.noformat=true "compile" >> "$LOG_FILE" 2>&1
    COMPILE_EXIT_CODE=$?

    if [ $COMPILE_EXIT_CODE -ne 0 ]; then
        echo "   -> ❌ ERROR:   $SUBMISSION_ID" | tee -a "$LOG_FILE"
        let COMPILE_ERROR_SUBMISSIONS++
        rm $TARGET_FILE_PATH
        rm $TMP_ORIGINAL
        popd > /dev/null
        continue
    fi
    
    sbt --client -Dsbt.log.noformat=true "scalafix" > "$TMP_RAW_LINT_OUTPUT" 2>&1
    SCALAFIX_EXIT_CODE=$?
    cat "$TMP_RAW_LINT_OUTPUT" >> "$LOG_FILE"

    ESCAPED_ROOT=$(echo "$ROOT" | sed 's/[\/&]/\\&/g')
    
    grep "^\[error\]" "$TMP_RAW_LINT_OUTPUT" | \
    grep -v "Total time" | \
    grep -v "scalafix.sbt.ScalafixFailed" | \
    sed "s#${ESCAPED_ROOT}/##" \
    > "$LINT_REPORT_FILE"

    sbt --client -Dsbt.log.noformat=true "scalafmt" >> "$LOG_FILE" 2>&1
    cp "$TARGET_FILE_PATH" "$TMP_REFACTORED"

    diff -u "$TMP_ORIGINAL" "$TMP_REFACTORED" > "$DIFF_OUTPUT_FILE"
    
    if [ $SCALAFIX_EXIT_CODE -ne 0 ]; then
        let RULE_MATCH_SUBMISSIONS++
        echo "   -> ⚠️  ISSUES:  $SUBMISSION_ID" | tee -a "$LOG_FILE"
    else
        echo "   -> ✅ SUCCESS: $SUBMISSION_ID" | tee -a "$LOG_FILE"
    fi

    rm $TARGET_FILE_PATH
    rm $TMP_ORIGINAL
    rm $TMP_REFACTORED
    rm $TMP_RAW_LINT_OUTPUT
    
    popd > /dev/null
done

echo "Total submissions: $TOTAL_SUBMISSIONS" | tee -a "$LOG_FILE"
echo "Submissions with missing file: $MISSING_FILE_SUBMISSIONS" | tee -a "$LOG_FILE"
echo "Submissions with compile errors: $COMPILE_ERROR_SUBMISSIONS" | tee -a "$LOG_FILE"
echo "Submissions failing check: $RULE_MATCH_SUBMISSIONS" | tee -a "$LOG_FILE"
echo "-------------------------------------------------------" >> "$LOG_FILE"

echo "Grading complete. Final results summary in $LOG_FILE"