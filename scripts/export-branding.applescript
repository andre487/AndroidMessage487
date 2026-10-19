on run argv
    set scriptFile to POSIX file (item 1 of argv) as alias
    set sourceFile to item 2 of argv
    set outputDirectory to item 3 of argv
    with timeout of 600 seconds
        tell application "Adobe Illustrator"
            return do javascript scriptFile with arguments {sourceFile, outputDirectory}
        end tell
    end timeout
end run
