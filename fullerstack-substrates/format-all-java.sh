#!/bin/bash
# Format all Java files using VS Code's formatter

# Find all Java files and format them one by one using VS Code CLI
find src -name "*.java" -type f | while read file; do
    echo "Formatting: $file"
    code --wait "$file" --execute-command "editor.action.formatDocument" 2>/dev/null || true
done

echo "Done formatting"
