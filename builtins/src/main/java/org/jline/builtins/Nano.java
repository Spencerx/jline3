/*
 * Copyright (c) 2002-2025, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.reader.Editor;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.ControlChar;
import org.jline.terminal.Attributes.InputFlag;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.Terminal.SignalHandler;
import org.jline.terminal.impl.MouseSupport;
import org.jline.utils.*;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.Status;
import org.mozilla.universalchardet.UniversalDetector;

import static org.jline.builtins.SyntaxHighlighter.*;
import static org.jline.keymap.KeyMap.KEYMAP_LENGTH;
import static org.jline.keymap.KeyMap.alt;
import static org.jline.keymap.KeyMap.ctrl;
import static org.jline.keymap.KeyMap.del;
import static org.jline.keymap.KeyMap.key;
import static org.jline.keymap.KeyMap.translate;

/**
 * A terminal text editor similar to the 'nano' Unix command.
 * <p>
 * This class provides a text editor with features including:
 * </p>
 * <ul>
 *   <li>File editing with save functionality</li>
 *   <li>Cut, copy, and paste operations</li>
 *   <li>Search and replace functionality</li>
 *   <li>Syntax highlighting</li>
 *   <li>Undo/redo support</li>
 *   <li>Line numbering</li>
 * </ul>
 * <p>
 * The implementation supports many of the key bindings and features of the
 * traditional Unix 'nano' editor, adapted for JLine's terminal handling.
 * It implements the JLine Editor interface for integration with other components.
 * </p>
 */
@SuppressWarnings({"unused", "SameParameterValue", "BooleanMethodIsAlwaysInverted"})
public class Nano implements Editor {

    // Final fields
    protected final Terminal terminal;
    protected final Display display;
    protected final BindingReader bindingReader;
    protected final Size size;
    protected final Path root;
    protected final int vsusp;
    private final List<Path> syntaxFiles = new ArrayList<>();

    // Keys
    protected KeyMap<Operation> keys;

    // Configuration
    public String title = "JLine Nano 3.0.0";
    public boolean printLineNumbers = false;
    public boolean wrapping = false;
    public boolean smoothScrolling = true;
    public boolean mouseSupport = false;
    public Terminal.MouseTracking mouseTracking = Terminal.MouseTracking.Off;
    public boolean oneMoreLine = true;
    public boolean constantCursor = false;
    public boolean quickBlank = false;
    public int tabs = 4;
    public String brackets = "\"’)>]}";
    public String matchBrackets = "(<[{)>]}";
    public String punct = "!.?";
    public String quoteStr = "^([ \\t]*[#:>\\|}])+";
    private boolean restricted = false;
    private String syntaxName;
    private boolean writeBackup = false;
    private boolean atBlanks = false;
    private boolean view = false;
    private boolean cut2end = false;
    private boolean tempFile = false;
    private String historyLog = null;
    private boolean tabsToSpaces = false;
    private boolean autoIndent = false;

    // Input
    protected final List<Buffer> buffers = new ArrayList<>();
    protected int bufferIndex;
    protected Buffer buffer;

    protected String message;
    protected String errorMessage = null;
    protected int nbBindings = 0;

    protected LinkedHashMap<String, String> shortcuts;

    protected String editMessage;
    protected final StringBuilder editBuffer = new StringBuilder();

    protected boolean searchCaseSensitive;
    protected boolean searchRegexp;
    protected boolean searchBackwards;
    protected String searchTerm;
    protected int matchedLength = -1;
    protected PatternHistory patternHistory = new PatternHistory(null);
    protected WriteMode writeMode = WriteMode.WRITE;
    protected List<String> cutbuffer = new ArrayList<>();
    protected boolean mark = false;
    protected boolean highlight = true;
    private boolean searchToReplace = false;
    protected boolean readNewBuffer = true;
    private boolean nanorcIgnoreErrors;
    private final boolean windowsTerminal;
    private boolean insertHelp = false;
    private boolean help = false;
    private Box suggestionBox;
    private Map<AttributedString, List<AttributedString>> suggestions;
    private int mouseX;
    private int mouseY;

    protected enum WriteMode {
        WRITE,
        APPEND,
        PREPEND
    }

    protected enum WriteFormat {
        UNIX,
        DOS,
        MAC
    }

    protected enum CursorMovement {
        RIGHT,
        LEFT,
        STILL
    }

    /**
     * Interface representing a diagnostic message for code in the editor.
     * <p>
     * Diagnostics are used to highlight issues in the code and display tooltips
     * with error messages or warnings. They define a region in the text (from start
     * line/column to end line/column) and a message to display when hovering over
     * that region.
     * </p>
     */
    public interface Diagnostic {
        /**
         * Gets the starting line number of the diagnostic region (1-based).
         *
         * @return the starting line number
         */
        int getStartLine();

        /**
         * Gets the starting column number of the diagnostic region (0-based).
         *
         * @return the starting column number
         */
        int getStartColumn();

        /**
         * Gets the ending line number of the diagnostic region (1-based).
         *
         * @return the ending line number
         */
        int getEndLine();

        /**
         * Gets the ending column number of the diagnostic region (0-based).
         *
         * @return the ending column number
         */
        int getEndColumn();

        /**
         * Gets the message to display for this diagnostic.
         *
         * @return the diagnostic message
         */
        String getMessage();
    }

    public static String[] usage() {
        return new String[] {
            "nano -  edit files",
            "Usage: nano [OPTIONS] [FILES]",
            "  -? --help                    Show help",
            "  -B --backup                  When saving a file, back up the previous version of it, using the current filename",
            "                               suffixed with a tilde (~).",
            "  -I --ignorercfiles           Don't look at the system's nanorc nor at the user's nanorc.",
            "  -Q --quotestr=regex          Set the regular expression for matching the quoting part of a line.",
            "  -T --tabsize=number          Set the size (width) of a tab to number columns.",
            "  -U --quickblank              Do quick status-bar blanking: status-bar messages will disappear after 1 keystroke.",
            "  -c --constantshow            Constantly show the cursor position on the status bar.",
            "  -e --emptyline               Do not use the line below the title bar, leaving it entirely blank.",
            "  -j --jumpyscrolling          Scroll the buffer contents per half-screen instead of per line.",
            "  -l --linenumbers             Display line numbers to the left of the text area.",
            "  -m --mouse                   Enable mouse support, if available for your system.",
            "  -$ --softwrap                Enable 'soft wrapping'. ",
            "  -a --atblanks                Wrap lines at whitespace instead of always at the edge of the screen.",
            "  -R --restricted              Restricted mode: don't allow suspending; don't allow a file to be appended to,",
            "                               prepended to, or saved under a different name if it already has one;",
            "                               and don't use backup files.",
            "  -Y --syntax=name             The name of the syntax highlighting to use.",
            "  -z --suspend                 Enable the ability to suspend nano using the system's suspend keystroke (usually ^Z).",
            "  -v --view                    Don't allow the contents of the file to be altered: read-only mode.",
            "  -k --cutfromcursor           Make the 'Cut Text' command cut from the current cursor position to the end of the line",
            "  -t --tempfile                Save a changed buffer without prompting (when exiting with ^X).",
            "  -H --historylog=name         Log search strings to file, so they can be retrieved in later sessions",
            "  -E --tabstospaces            Convert typed tabs to spaces.",
            "  -i --autoindent              Indent new lines to the previous line's indentation."
        };
    }

    @SuppressWarnings("StatementWithEmptyBody")
    protected class Buffer {
        String file;
        Charset charset;
        WriteFormat format = WriteFormat.UNIX;
        List<String> lines;

        int firstLineToDisplay;
        int firstColumnToDisplay = 0;
        int offsetInLineToDisplay;

        int line;
        List<LinkedList<Integer>> offsets = new ArrayList<>();
        int offsetInLine;
        int column;
        int wantedColumn;
        boolean uncut = false;
        int[] markPos = {-1, -1}; // line, offsetInLine + column
        SyntaxHighlighter syntaxHighlighter;

        boolean dirty;

        protected Buffer(String file) {
            this.file = file;
            this.syntaxHighlighter = SyntaxHighlighter.build(syntaxFiles, file, syntaxName, nanorcIgnoreErrors);
        }

        public void setDirty(boolean dirty) {
            this.dirty = dirty;
        }

        public String getFile() {
            return file;
        }

        public List<String> getLines() {
            return lines;
        }

        public int getFirstLineToDisplay() {
            return firstLineToDisplay;
        }

        public int getFirstColumnToDisplay() {
            return firstColumnToDisplay;
        }

        public int getOffsetInLineToDisplay() {
            return offsetInLineToDisplay;
        }

        public int getLine() {
            return line;
        }

        public Charset getCharset() {
            return charset;
        }

        public WriteFormat getFormat() {
            return format;
        }

        public boolean isDirty() {
            return dirty;
        }

        public SyntaxHighlighter getSyntaxHighlighter() {
            return syntaxHighlighter;
        }

        public int getOffsetInLine() {
            return offsetInLine;
        }

        public int getColumn() {
            return column;
        }

        public void open() throws IOException {
            if (lines != null) {
                return;
            }

            lines = new ArrayList<>();
            lines.add("");
            charset = Charset.defaultCharset();
            computeAllOffsets();

            if (file == null) {
                return;
            }

            Path path = root.resolve(file);
            if (Files.isDirectory(path)) {
                setMessage("\"" + file + "\" is a directory");
                return;
            }

            try (InputStream fis = Files.newInputStream(path)) {
                read(fis);
            } catch (IOException e) {
                setMessage("Error reading " + file + ": " + e.getMessage());
            }
        }

        public void open(InputStream is) throws IOException {
            if (lines != null) {
                return;
            }

            lines = new ArrayList<>();
            lines.add("");
            charset = Charset.defaultCharset();
            computeAllOffsets();

            read(is);
        }

        public void read(InputStream fis) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int remaining;
            while ((remaining = fis.read(buffer)) > 0) {
                bos.write(buffer, 0, remaining);
            }
            byte[] bytes = bos.toByteArray();

            try {
                UniversalDetector detector = new UniversalDetector(null);
                detector.handleData(bytes, 0, bytes.length);
                detector.dataEnd();
                if (detector.getDetectedCharset() != null) {
                    charset = Charset.forName(detector.getDetectedCharset());
                }
            } catch (Throwable t) {
                // Ignore
            }

            // TODO: detect format, do not eat last newline
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset))) {
                String line;
                lines.clear();
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            if (lines.isEmpty()) {
                lines.add("");
            }
            computeAllOffsets();
            moveToChar(0);
        }

        private int charPosition(int displayPosition) {
            return charPosition(line, displayPosition, CursorMovement.STILL);
        }

        private int charPosition(int displayPosition, CursorMovement move) {
            return charPosition(line, displayPosition, move);
        }

        private int charPosition(int line, int displayPosition) {
            return charPosition(line, displayPosition, CursorMovement.STILL);
        }

        private int charPosition(int line, int displayPosition, CursorMovement move) {
            int out = lines.get(line).length();
            if (!lines.get(line).contains("\t") || displayPosition == 0) {
                out = displayPosition;
            } else if (displayPosition < length(lines.get(line))) {
                int rdiff = 0;
                int ldiff = 0;
                for (int i = 0; i < lines.get(line).length(); i++) {
                    int dp = length(lines.get(line).substring(0, i));
                    if (move == CursorMovement.LEFT) {
                        if (dp <= displayPosition) {
                            out = i;
                        } else {
                            break;
                        }
                    } else if (move == CursorMovement.RIGHT) {
                        if (dp >= displayPosition) {
                            out = i;
                            break;
                        }
                    } else if (move == CursorMovement.STILL) {
                        if (dp <= displayPosition) {
                            ldiff = displayPosition - dp;
                            out = i;
                        } else {
                            rdiff = dp - displayPosition;
                            if (rdiff < ldiff) {
                                out = i;
                            }
                            break;
                        }
                    }
                }
            }
            return out;
        }

        String blanks(int nb) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nb; i++) {
                sb.append(' ');
            }
            return sb.toString();
        }

        public void insert(String insert) {
            String text = lines.get(line);
            int pos = charPosition(offsetInLine + column);
            insert = insert.replaceAll("\r\n", "\n");
            insert = insert.replaceAll("\r", "\n");
            if (tabsToSpaces && insert.length() == 1 && insert.charAt(0) == '\t') {
                int len = pos == text.length() ? length(text + insert) : length(text.substring(0, pos) + insert);
                insert = blanks(len - offsetInLine - column);
            }
            if (autoIndent && insert.length() == 1 && insert.charAt(0) == '\n') {
                String currentLine = lines.get(line);
                int indentLength = 0;
                while (indentLength < currentLine.length()
                        && (currentLine.charAt(indentLength) == ' ' || currentLine.charAt(indentLength) == '\t')) {
                    indentLength++;
                }
                if (indentLength > 0) {
                    insert += currentLine.substring(0, indentLength);
                }
            }
            String mod;
            String tail = "";
            if (pos == text.length()) {
                mod = text + insert;
            } else {
                mod = text.substring(0, pos) + insert;
                tail = text.substring(pos);
            }
            List<String> ins = new ArrayList<>();
            int last = 0;
            int idx = mod.indexOf('\n', last);
            while (idx >= 0) {
                ins.add(mod.substring(last, idx));
                last = idx + 1;
                idx = mod.indexOf('\n', last);
            }
            ins.add(mod.substring(last) + tail);
            int curPos = length(mod.substring(last));
            lines.set(line, ins.get(0));
            offsets.set(line, computeOffsets(ins.get(0)));
            for (int i = 1; i < ins.size(); i++) {
                ++line;
                lines.add(line, ins.get(i));
                offsets.add(line, computeOffsets(ins.get(i)));
            }
            moveToChar(curPos);
            ensureCursorVisible();
            dirty = true;
        }

        void computeAllOffsets() {
            offsets.clear();
            for (String text : lines) {
                offsets.add(computeOffsets(text));
            }
        }

        LinkedList<Integer> computeOffsets(String line) {
            String text = new AttributedStringBuilder().tabs(tabs).append(line).toString();
            int width = size.getColumns() - (printLineNumbers ? 8 : 0);
            LinkedList<Integer> offsets = new LinkedList<>();
            offsets.add(0);
            if (wrapping) {
                int last = 0;
                int prevword = 0;
                boolean inspace = false;
                for (int i = 0; i < text.length(); i++) {
                    if (isBreakable(text.charAt(i))) {
                        inspace = true;
                    } else if (inspace) {
                        prevword = i;
                        inspace = false;
                    }
                    if (i == last + width - 1) {
                        if (prevword == last) {
                            prevword = i;
                        }
                        offsets.add(prevword);
                        last = prevword;
                    }
                }
            }
            return offsets;
        }

        public boolean isBreakable(char ch) {
            return !atBlanks || ch == ' ';
        }

        public void moveToChar(int pos) {
            moveToChar(pos, CursorMovement.STILL);
        }

        public void moveToChar(int pos, CursorMovement move) {
            if (!wrapping) {
                if (pos > column && pos - firstColumnToDisplay + 1 > width()) {
                    firstColumnToDisplay = offsetInLine + column - 6;
                } else if (pos < column && firstColumnToDisplay + 5 > pos) {
                    firstColumnToDisplay = Math.max(0, firstColumnToDisplay - width() + 5);
                }
            }
            if (lines.get(line).contains("\t")) {
                int cpos = charPosition(pos, move);
                if (cpos < lines.get(line).length()) {
                    pos = length(lines.get(line).substring(0, cpos));
                } else {
                    pos = length(lines.get(line));
                }
            }
            offsetInLine = prevLineOffset(line, pos + 1).get();
            column = pos - offsetInLine;
        }

        public void delete(int count) {
            while (--count >= 0 && moveRight(1) && backspace(1))
                ;
        }

        public boolean backspace(int count) {
            while (count > 0) {
                String text = lines.get(line);
                int pos = charPosition(offsetInLine + column);
                if (pos == 0) {
                    if (line == 0) {
                        bof();
                        return false;
                    }
                    String prev = lines.get(--line);
                    lines.set(line, prev + text);
                    offsets.set(line, computeOffsets(prev + text));
                    moveToChar(length(prev));
                    lines.remove(line + 1);
                    offsets.remove(line + 1);
                    count--;
                } else {
                    int nb = Math.min(pos, count);
                    int curPos = length(text.substring(0, pos - nb));
                    text = text.substring(0, pos - nb) + text.substring(pos);
                    lines.set(line, text);
                    offsets.set(line, computeOffsets(text));
                    moveToChar(curPos);
                    count -= nb;
                }
                dirty = true;
            }
            ensureCursorVisible();
            return true;
        }

        public boolean moveLeft(int chars) {
            boolean ret = true;
            while (--chars >= 0) {
                if (offsetInLine + column > 0) {
                    moveToChar(offsetInLine + column - 1, CursorMovement.LEFT);
                } else if (line > 0) {
                    line--;
                    moveToChar(length(getLine(line)));
                } else {
                    bof();
                    ret = false;
                    break;
                }
            }
            wantedColumn = column;
            ensureCursorVisible();
            return ret;
        }

        public boolean moveRight(int chars) {
            return moveRight(chars, false);
        }

        public int width() {
            return size.getColumns()
                    - (printLineNumbers ? 8 : 0)
                    - (wrapping ? 0 : 1)
                    - (firstColumnToDisplay > 0 ? 1 : 0);
        }

        public boolean moveRight(int chars, boolean fromBeginning) {
            if (fromBeginning) {
                firstColumnToDisplay = 0;
                offsetInLine = 0;
                column = 0;
                chars = Math.min(chars, length(getLine(line)));
            }
            boolean ret = true;
            while (--chars >= 0) {
                int len = length(getLine(line));
                if (offsetInLine + column + 1 <= len) {
                    moveToChar(offsetInLine + column + 1, CursorMovement.RIGHT);
                } else if (getLine(line + 1) != null) {
                    line++;
                    firstColumnToDisplay = 0;
                    offsetInLine = 0;
                    column = 0;
                } else {
                    eof();
                    ret = false;
                    break;
                }
            }
            wantedColumn = column;
            ensureCursorVisible();
            return ret;
        }

        public void moveDown(int lines) {
            cursorDown(lines);
            ensureCursorVisible();
        }

        public void moveUp(int lines) {
            cursorUp(lines);
            ensureCursorVisible();
        }

        private Optional<Integer> prevLineOffset(int line, int offsetInLine) {
            if (line >= offsets.size()) {
                return Optional.empty();
            }
            Iterator<Integer> it = offsets.get(line).descendingIterator();
            while (it.hasNext()) {
                int off = it.next();
                if (off < offsetInLine) {
                    return Optional.of(off);
                }
            }
            return Optional.empty();
        }

        private Optional<Integer> nextLineOffset(int line, int offsetInLine) {
            if (line >= offsets.size()) {
                return Optional.empty();
            }
            return offsets.get(line).stream().filter(o -> o > offsetInLine).findFirst();
        }

        public void moveDisplayDown(int lines) {
            int height =
                    size.getRows() - computeHeader().size() - computeFooter().size();
            // Adjust cursor
            while (--lines >= 0) {
                int lastLineToDisplay = firstLineToDisplay;
                if (!wrapping) {
                    lastLineToDisplay += height - 1;
                } else {
                    int off = offsetInLineToDisplay;
                    for (int l = 0; l < height - 1; l++) {
                        Optional<Integer> next = nextLineOffset(lastLineToDisplay, off);
                        if (next.isPresent()) {
                            off = next.get();
                        } else {
                            off = 0;
                            lastLineToDisplay++;
                        }
                    }
                }
                if (getLine(lastLineToDisplay) == null) {
                    eof();
                    return;
                }
                Optional<Integer> next = nextLineOffset(firstLineToDisplay, offsetInLineToDisplay);
                if (next.isPresent()) {
                    offsetInLineToDisplay = next.get();
                } else {
                    offsetInLineToDisplay = 0;
                    firstLineToDisplay++;
                }
            }
        }

        public void moveDisplayUp(int lines) {
            int width = size.getColumns() - (printLineNumbers ? 8 : 0);
            while (--lines >= 0) {
                if (offsetInLineToDisplay > 0) {
                    offsetInLineToDisplay = Math.max(0, offsetInLineToDisplay - (width - 1));
                } else if (firstLineToDisplay > 0) {
                    firstLineToDisplay--;
                    offsetInLineToDisplay = prevLineOffset(firstLineToDisplay, Integer.MAX_VALUE)
                            .get();
                } else {
                    bof();
                    return;
                }
            }
        }

        private void cursorDown(int lines) {
            // Adjust cursor
            firstColumnToDisplay = 0;
            while (--lines >= 0) {
                if (!wrapping) {
                    if (getLine(line + 1) != null) {
                        line++;
                        offsetInLine = 0;
                        column = Math.min(length(getLine(line)), wantedColumn);
                    } else {
                        bof();
                        break;
                    }
                } else {
                    String txt = getLine(line);
                    Optional<Integer> off = nextLineOffset(line, offsetInLine);
                    if (off.isPresent()) {
                        offsetInLine = off.get();
                    } else if (getLine(line + 1) == null) {
                        eof();
                        break;
                    } else {
                        line++;
                        offsetInLine = 0;
                        txt = getLine(line);
                    }
                    int next = nextLineOffset(line, offsetInLine).orElse(length(txt));
                    column = Math.min(wantedColumn, next - offsetInLine);
                }
            }
            moveToChar(offsetInLine + column);
        }

        private void cursorUp(int lines) {
            firstColumnToDisplay = 0;
            while (--lines >= 0) {
                if (!wrapping) {
                    if (line > 0) {
                        line--;
                        column = Math.min(length(getLine(line)) - offsetInLine, wantedColumn);
                    } else {
                        bof();
                        break;
                    }
                } else {
                    Optional<Integer> prev = prevLineOffset(line, offsetInLine);
                    if (prev.isPresent()) {
                        offsetInLine = prev.get();
                    } else if (line > 0) {
                        line--;
                        offsetInLine = prevLineOffset(line, Integer.MAX_VALUE).get();
                        int next = nextLineOffset(line, offsetInLine).orElse(length(getLine(line)));
                        column = Math.min(wantedColumn, next - offsetInLine);
                    } else {
                        bof();
                        break;
                    }
                }
            }
            moveToChar(offsetInLine + column);
        }

        void ensureCursorVisible() {
            List<AttributedString> header = computeHeader();
            int rwidth = size.getColumns();
            int height = size.getRows() - header.size() - computeFooter().size();

            while (line < firstLineToDisplay || line == firstLineToDisplay && offsetInLine < offsetInLineToDisplay) {
                moveDisplayUp(smoothScrolling ? 1 : height / 2);
            }

            while (true) {
                int cursor =
                        computeCursorPosition(header.size() * size.getColumns() + (printLineNumbers ? 8 : 0), rwidth);
                if (cursor >= (height + header.size()) * rwidth) {
                    moveDisplayDown(smoothScrolling ? 1 : height / 2);
                } else {
                    break;
                }
            }
        }

        void eof() {}

        void bof() {}

        void resetDisplay() {
            column = offsetInLine + column;
            moveRight(column, true);
        }

        String getLine(int line) {
            return line < lines.size() ? lines.get(line) : null;
        }

        String getTitle() {
            return file != null ? "File: " + file : "New Buffer";
        }

        List<AttributedString> computeHeader() {
            String left = Nano.this.getTitle();
            String middle = null;
            String right = dirty ? "Modified" : "        ";

            int width = size.getColumns();
            int mstart = 2 + left.length() + 1;
            int mend = width - 2 - 8;

            if (file == null) {
                middle = "New Buffer";
            } else {
                int max = mend - mstart;
                String src = file;
                if ("File: ".length() + src.length() > max) {
                    int lastSep = src.lastIndexOf('/');
                    if (lastSep > 0) {
                        String p1 = src.substring(lastSep);
                        String p0 = src.substring(0, lastSep);
                        while (p0.startsWith(".")) {
                            p0 = p0.substring(1);
                        }
                        int nb = max - p1.length() - "File: ...".length();
                        int cut;
                        cut = Math.max(0, Math.min(p0.length(), p0.length() - nb));
                        middle = "File: ..." + p0.substring(cut) + p1;
                    }
                    if (middle == null || middle.length() > max) {
                        left = null;
                        max = mend - 2;
                        int nb = max - "File: ...".length();
                        int cut = Math.max(0, Math.min(src.length(), src.length() - nb));
                        middle = "File: ..." + src.substring(cut);
                        if (middle.length() > max) {
                            middle = middle.substring(0, max);
                        }
                    }
                } else {
                    middle = "File: " + src;
                }
            }

            int pos = 0;
            AttributedStringBuilder sb = new AttributedStringBuilder();
            sb.style(AttributedStyle.INVERSE);
            sb.append("  ");
            pos += 2;

            if (left != null) {
                sb.append(left);
                pos += left.length();
                sb.append(" ");
                pos += 1;
                for (int i = 1; i < (size.getColumns() - middle.length()) / 2 - left.length() - 1 - 2; i++) {
                    sb.append(" ");
                    pos++;
                }
            }
            sb.append(middle);
            pos += middle.length();
            while (pos < width - 8 - 2) {
                sb.append(" ");
                pos++;
            }
            sb.append(right);
            sb.append("  \n");
            if (oneMoreLine) {
                return Collections.singletonList(sb.toAttributedString());
            } else {
                return Arrays.asList(sb.toAttributedString(), new AttributedString("\n"));
            }
        }

        void highlightDisplayedLine(int curLine, int curOffset, int nextOffset, AttributedStringBuilder line) {
            AttributedString disp = highlight
                    ? syntaxHighlighter.highlight(
                            new AttributedStringBuilder().tabs(tabs).append(getLine(curLine)))
                    : new AttributedStringBuilder()
                            .tabs(tabs)
                            .append(getLine(curLine))
                            .toAttributedString();
            int[] hls = highlightStart();
            int[] hle = highlightEnd();
            if (hls[0] == -1 || hle[0] == -1) {
                line.append(disp.columnSubSequence(curOffset, nextOffset));
            } else if (hls[0] == hle[0]) {
                if (curLine == hls[0]) {
                    if (hls[1] > nextOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset));
                    } else if (hls[1] < curOffset) {
                        if (hle[1] > nextOffset) {
                            line.append(disp.columnSubSequence(curOffset, nextOffset), AttributedStyle.INVERSE);
                        } else if (hle[1] > curOffset) {
                            line.append(disp.columnSubSequence(curOffset, hle[1]), AttributedStyle.INVERSE);
                            line.append(disp.columnSubSequence(hle[1], nextOffset));
                        } else {
                            line.append(disp.columnSubSequence(curOffset, nextOffset));
                        }
                    } else {
                        line.append(disp.columnSubSequence(curOffset, hls[1]));
                        if (hle[1] > nextOffset) {
                            line.append(disp.columnSubSequence(hls[1], nextOffset), AttributedStyle.INVERSE);
                        } else {
                            line.append(disp.columnSubSequence(hls[1], hle[1]), AttributedStyle.INVERSE);
                            line.append(disp.columnSubSequence(hle[1], nextOffset));
                        }
                    }
                } else {
                    line.append(disp.columnSubSequence(curOffset, nextOffset));
                }
            } else {
                if (curLine > hls[0] && curLine < hle[0]) {
                    line.append(disp.columnSubSequence(curOffset, nextOffset), AttributedStyle.INVERSE);
                } else if (curLine == hls[0]) {
                    if (hls[1] > nextOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset));
                    } else if (hls[1] < curOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset), AttributedStyle.INVERSE);
                    } else {
                        line.append(disp.columnSubSequence(curOffset, hls[1]));
                        line.append(disp.columnSubSequence(hls[1], nextOffset), AttributedStyle.INVERSE);
                    }
                } else if (curLine == hle[0]) {
                    if (hle[1] < curOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset));
                    } else if (hle[1] > nextOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset), AttributedStyle.INVERSE);
                    } else {
                        line.append(disp.columnSubSequence(curOffset, hle[1]), AttributedStyle.INVERSE);
                        line.append(disp.columnSubSequence(hle[1], nextOffset));
                    }
                } else {
                    line.append(disp.columnSubSequence(curOffset, nextOffset));
                }
            }
        }

        List<AttributedString> getDisplayedLines(int nbLines, List<Diagnostic> diagnostics) {
            AttributedStyle s = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK + AttributedStyle.BRIGHT);
            AttributedString cut = new AttributedString("…", s);
            AttributedString ret = new AttributedString("↩", s);

            List<AttributedString> newLines = new ArrayList<>();
            int rwidth = size.getColumns();
            int width = rwidth - (printLineNumbers ? 8 : 0);
            int curLine = firstLineToDisplay;
            int curOffset = offsetInLineToDisplay;
            int prevLine = -1;
            if (highlight) {
                syntaxHighlighter.reset();
                for (int i = Math.max(0, curLine - nbLines); i < curLine; i++) {
                    syntaxHighlighter.highlight(getLine(i));
                }
            }
            for (int terminalLine = 0; terminalLine < nbLines; terminalLine++) {
                AttributedStringBuilder line = new AttributedStringBuilder().tabs(tabs);
                if (printLineNumbers && curLine < lines.size()) {
                    line.style(s);
                    if (curLine != prevLine) {
                        line.append(String.format("%7d ", curLine + 1));
                    } else {
                        line.append("      ‧ ");
                    }
                    line.style(AttributedStyle.DEFAULT);
                    prevLine = curLine;
                }
                if (curLine < lines.size()) {
                    if (!wrapping) {
                        AttributedString disp = new AttributedStringBuilder()
                                .tabs(tabs)
                                .append(getLine(curLine))
                                .toAttributedString();
                        if (this.line == curLine) {
                            int cutCount = 1;
                            if (firstColumnToDisplay > 0) {
                                line.append(cut);
                                cutCount = 2;
                            }
                            if (disp.columnLength() - firstColumnToDisplay
                                    >= width - (cutCount - 1) * cut.columnLength()) {
                                highlightDisplayedLine(
                                        curLine,
                                        firstColumnToDisplay,
                                        firstColumnToDisplay + width - cutCount * cut.columnLength(),
                                        line);
                                line.append(cut);
                            } else {
                                highlightDisplayedLine(curLine, firstColumnToDisplay, disp.columnLength(), line);
                            }
                        } else {
                            if (disp.columnLength() >= width) {
                                highlightDisplayedLine(curLine, 0, width - cut.columnLength(), line);
                                line.append(cut);
                            } else {
                                highlightDisplayedLine(curLine, 0, disp.columnLength(), line);
                            }
                        }
                        curLine++;
                    } else {
                        Optional<Integer> nextOffset = nextLineOffset(curLine, curOffset);
                        if (nextOffset.isPresent()) {
                            highlightDisplayedLine(curLine, curOffset, nextOffset.get(), line);
                            line.append(ret);
                            curOffset = nextOffset.get();
                        } else {
                            highlightDisplayedLine(curLine, curOffset, Integer.MAX_VALUE, line);
                            curLine++;
                            curOffset = 0;
                        }
                    }
                }
                line.append('\n');
                newLines.add(line.toAttributedString());
            }
            // add tool tips if any
            if (diagnostics != null) {
                for (Diagnostic diagnostic : diagnostics) {
                    // TODO when they aren't on the same line
                    if (diagnostic.getStartLine() == diagnostic.getEndLine()) {
                        int line = diagnostic.getEndLine() - firstLineToDisplay;
                        AttributedString attributedString = newLines.get(line);
                        AttributedStringBuilder builder = new AttributedStringBuilder(attributedString.length());
                        builder.append(attributedString.subSequence(0, diagnostic.getStartColumn()));
                        builder.append(
                                attributedString.subSequence(diagnostic.getStartColumn(), diagnostic.getEndColumn()),
                                AttributedStyle.DEFAULT.underline().foreground(AttributedStyle.RED));
                        builder.append(
                                attributedString.subSequence(diagnostic.getEndColumn(), attributedString.length()));
                        newLines.set(line, builder.toAttributedString());
                        if (line == mouseY - 1
                                && mouseX >= diagnostic.getStartColumn()
                                && mouseX <= diagnostic.getEndColumn()) {
                            String message = diagnostic.getMessage();
                            if (message == null || message.isEmpty()) {
                                continue;
                            }
                            // build tool tip box
                            int xi = diagnostic.getStartColumn();
                            int dBoxSize = message.length() + 2;
                            int maxWidth = (int) Math.round(
                                    (size.getColumns() - xi) * 0.60); // let's do 60% of what's left of the screen
                            int xl = Math.min(dBoxSize + xi, xi + maxWidth);
                            // adjust content
                            List<AttributedString> boxLines = adjustLines(
                                    Collections.singletonList(new AttributedString(message)),
                                    dBoxSize - 2,
                                    xl - xi - 2);
                            // Position the box below the current position
                            int yi = diagnostic.getStartLine() - firstLineToDisplay + 1;
                            int yl = yi + boxLines.size() + 1;
                            if (yl >= newLines.size()) {
                                // move above
                                yi = diagnostic.getStartLine() - firstLineToDisplay - boxLines.size() - 2;
                                yl = yi + boxLines.size() + 1;
                                if (yi < 0) {
                                    continue;
                                }
                            }
                            Box box = new Box(xi, yi, xl, yl);
                            box.setLines(boxLines);
                            box.draw(newLines);
                        }
                    }
                }
            }
            return newLines;
        }

        public void moveTo(int x, int y) {
            if (printLineNumbers) {
                x = Math.max(x - 8, 0);
            }
            line = firstLineToDisplay;
            offsetInLine = offsetInLineToDisplay;
            wantedColumn = x;
            cursorDown(y);
        }

        public void gotoLine(int x, int y) {
            line = y < lines.size() ? y : lines.size() - 1;
            x = Math.min(x, length(lines.get(line)));
            firstLineToDisplay = line > 0 ? line - 1 : line;
            offsetInLine = 0;
            offsetInLineToDisplay = 0;
            column = 0;
            moveRight(x);
        }

        public int getDisplayedCursor() {
            return computeCursorPosition(printLineNumbers ? 8 : 0, size.getColumns() + 1);
        }

        private int computeCursorPosition(int cursor, int rwidth) {
            int cur = firstLineToDisplay;
            int off = offsetInLineToDisplay;
            while (true) {
                if (cur < line || off < offsetInLine) {
                    if (!wrapping) {
                        cursor += rwidth;
                        cur++;
                    } else {
                        cursor += rwidth;
                        Optional<Integer> next = nextLineOffset(cur, off);
                        if (next.isPresent()) {
                            off = next.get();
                        } else {
                            cur++;
                            off = 0;
                        }
                    }
                } else if (cur == line) {
                    if (!wrapping && column > firstColumnToDisplay + width()) {
                        while (column > firstColumnToDisplay + width()) {
                            firstColumnToDisplay += width();
                        }
                    }
                    cursor += column - firstColumnToDisplay + (firstColumnToDisplay > 0 ? 1 : 0);
                    break;
                } else {
                    throw new IllegalStateException();
                }
            }
            return cursor;
        }

        char getCurrentChar() {
            String str = lines.get(line);
            if (column + offsetInLine < str.length()) {
                return str.charAt(column + offsetInLine);
            } else if (line < lines.size() - 1) {
                return '\n';
            } else {
                return 0;
            }
        }

        @SuppressWarnings("StatementWithEmptyBody")
        public void prevWord() {
            while (Character.isAlphabetic(getCurrentChar()) && moveLeft(1))
                ;
            while (!Character.isAlphabetic(getCurrentChar()) && moveLeft(1))
                ;
            while (Character.isAlphabetic(getCurrentChar()) && moveLeft(1))
                ;
            moveRight(1);
        }

        @SuppressWarnings("StatementWithEmptyBody")
        public void nextWord() {
            while (Character.isAlphabetic(getCurrentChar()) && moveRight(1))
                ;
            while (!Character.isAlphabetic(getCurrentChar()) && moveRight(1))
                ;
        }

        public void beginningOfLine() {
            column = offsetInLine = 0;
            wantedColumn = 0;
            ensureCursorVisible();
        }

        public void endOfLine() {
            int x = length(lines.get(line));
            moveRight(x, true);
        }

        public void prevPage() {
            int height =
                    size.getRows() - computeHeader().size() - computeFooter().size();
            scrollUp(height - 2);
            column = 0;
            firstLineToDisplay = line;
            offsetInLineToDisplay = offsetInLine;
        }

        public void nextPage() {
            int height =
                    size.getRows() - computeHeader().size() - computeFooter().size();
            scrollDown(height - 2);
            column = 0;
            firstLineToDisplay = line;
            offsetInLineToDisplay = offsetInLine;
        }

        public void scrollUp(int lines) {
            cursorUp(lines);
            moveDisplayUp(lines);
        }

        public void scrollDown(int lines) {
            cursorDown(lines);
            moveDisplayDown(lines);
        }

        public void firstLine() {
            line = 0;
            offsetInLine = column = 0;
            ensureCursorVisible();
        }

        public void lastLine() {
            line = lines.size() - 1;
            offsetInLine = column = 0;
            ensureCursorVisible();
        }

        boolean nextSearch() {
            boolean out = false;
            if (searchTerm == null) {
                setMessage("No current search pattern");
                return false;
            }
            setMessage(null);
            int cur = line;
            int dir = searchBackwards ? -1 : +1;
            int newPos = -1;
            int newLine = -1;
            // Search on current line
            List<Integer> curRes = doSearch(lines.get(line));
            if (searchBackwards) {
                Collections.reverse(curRes);
            }
            for (int r : curRes) {
                if (searchBackwards ? r < offsetInLine + column : r > offsetInLine + column) {
                    newPos = r;
                    newLine = line;
                    break;
                }
            }
            // Check other lines
            if (newPos < 0) {
                while (true) {
                    cur = (cur + dir + lines.size()) % lines.size();
                    if (cur == line) {
                        break;
                    }
                    List<Integer> res = doSearch(lines.get(cur));
                    if (!res.isEmpty()) {
                        newPos = searchBackwards ? res.get(res.size() - 1) : res.get(0);
                        newLine = cur;
                        break;
                    }
                }
            }
            if (newPos < 0) {
                if (!curRes.isEmpty()) {
                    newPos = curRes.get(0);
                    newLine = line;
                }
            }
            if (newPos >= 0) {
                if (newLine == line && newPos == offsetInLine + column) {
                    setMessage("This is the only occurence");
                    return false;
                }
                if ((searchBackwards && (newLine > line || (newLine == line && newPos > offsetInLine + column)))
                        || (!searchBackwards
                                && (newLine < line || (newLine == line && newPos < offsetInLine + column)))) {
                    setMessage("Search Wrapped");
                }
                line = newLine;
                moveRight(newPos, true);
                out = true;
            } else {
                setMessage("\"" + searchTerm + "\" not found");
            }
            return out;
        }

        private List<Integer> doSearch(String text) {
            Pattern pat = Pattern.compile(
                    searchTerm,
                    (searchCaseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            | (searchRegexp ? 0 : Pattern.LITERAL));
            Matcher m = pat.matcher(text);
            List<Integer> res = new ArrayList<>();
            while (m.find()) {
                res.add(m.start());
                matchedLength = m.group(0).length();
            }
            return res;
        }

        protected int[] highlightStart() {
            int[] out = {-1, -1};
            if (mark) {
                out = getMarkStart();
            } else if (searchToReplace) {
                out[0] = line;
                out[1] = offsetInLine + column;
            }
            return out;
        }

        protected int[] highlightEnd() {
            int[] out = {-1, -1};
            if (mark) {
                out = getMarkEnd();
            } else if (searchToReplace && matchedLength > 0) {
                out[0] = line;
                int col = charPosition(offsetInLine + column) + matchedLength;
                if (col < lines.get(line).length()) {
                    out[1] = length(lines.get(line).substring(0, col));
                } else {
                    out[1] = length(lines.get(line));
                }
            }
            return out;
        }

        public void matching() {
            int opening = getCurrentChar();
            int idx = matchBrackets.indexOf(opening);
            if (idx >= 0) {
                int dir = (idx >= matchBrackets.length() / 2) ? -1 : +1;
                int closing = matchBrackets.charAt((idx + matchBrackets.length() / 2) % matchBrackets.length());

                int lvl = 1;
                int cur = line;
                int pos = offsetInLine + column;
                while (true) {
                    if ((pos + dir >= 0) && (pos + dir < getLine(cur).length())) {
                        pos += dir;
                    } else if ((cur + dir >= 0) && (cur + dir < lines.size())) {
                        cur += dir;
                        pos = dir > 0 ? 0 : lines.get(cur).length() - 1;
                        // Skip empty lines
                        if (pos < 0 || pos >= lines.get(cur).length()) {
                            continue;
                        }
                    } else {
                        setMessage("No matching bracket");
                        return;
                    }
                    int c = lines.get(cur).charAt(pos);
                    if (c == opening) {
                        lvl++;
                    } else if (c == closing) {
                        if (--lvl == 0) {
                            line = cur;
                            moveToChar(pos);
                            ensureCursorVisible();
                            return;
                        }
                    }
                }
            } else {
                setMessage("Not a bracket");
            }
        }

        private int length(String line) {
            return new AttributedStringBuilder().tabs(tabs).append(line).columnLength();
        }

        void copy() {
            if (uncut || cut2end || mark) {
                cutbuffer = new ArrayList<>();
            }
            if (mark) {
                int[] s = getMarkStart();
                int[] e = getMarkEnd();
                if (s[0] == e[0]) {
                    cutbuffer.add(lines.get(s[0]).substring(charPosition(s[0], s[1]), charPosition(e[0], e[1])));
                } else {
                    if (s[1] != 0) {
                        cutbuffer.add(lines.get(s[0]).substring(charPosition(s[0], s[1])));
                        s[0] = s[0] + 1;
                    }
                    for (int i = s[0]; i < e[0]; i++) {
                        cutbuffer.add(lines.get(i));
                    }
                    if (e[1] != 0) {
                        cutbuffer.add(lines.get(e[0]).substring(0, charPosition(e[0], e[1])));
                    }
                }
                mark = false;
                mark();
            } else if (cut2end) {
                String l = lines.get(line);
                int col = charPosition(offsetInLine + column);
                cutbuffer.add(l.substring(col));
                moveRight(l.substring(col).length());
            } else {
                cutbuffer.add(lines.get(line));
                cursorDown(1);
            }
            uncut = false;
        }

        void cut() {
            cut(false);
        }

        void cut(boolean toEnd) {
            if (lines.size() > 1) {
                if (uncut || cut2end || toEnd || mark) {
                    cutbuffer = new ArrayList<>();
                }
                if (mark) {
                    int[] s = getMarkStart();
                    int[] e = getMarkEnd();
                    if (s[0] == e[0]) {
                        String l = lines.get(s[0]);
                        int cols = charPosition(s[0], s[1]);
                        int cole = charPosition(e[0], e[1]);
                        cutbuffer.add(l.substring(cols, cole));
                        lines.set(s[0], l.substring(0, cols) + l.substring(cole));
                        computeAllOffsets();
                        moveRight(cols, true);
                    } else {
                        int ls = s[0];
                        int cs = charPosition(s[0], s[1]);
                        if (s[1] != 0) {
                            String l = lines.get(s[0]);
                            cutbuffer.add(l.substring(cs));
                            lines.set(s[0], l.substring(0, cs));
                            s[0] = s[0] + 1;
                        }
                        for (int i = s[0]; i < e[0]; i++) {
                            cutbuffer.add(lines.get(s[0]));
                            lines.remove(s[0]);
                        }
                        if (e[1] != 0) {
                            String l = lines.get(s[0]);
                            int col = charPosition(e[0], e[1]);
                            cutbuffer.add(l.substring(0, col));
                            lines.set(s[0], l.substring(col));
                        }
                        computeAllOffsets();
                        gotoLine(cs, ls);
                    }
                    mark = false;
                    mark();
                } else if (cut2end || toEnd) {
                    String l = lines.get(line);
                    int col = charPosition(offsetInLine + column);
                    cutbuffer.add(l.substring(col));
                    lines.set(line, l.substring(0, col));
                    if (toEnd) {
                        line++;
                        while (true) {
                            cutbuffer.add(lines.get(line));
                            lines.remove(line);
                            if (line > lines.size() - 1) {
                                line--;
                                break;
                            }
                        }
                    }
                } else {
                    cutbuffer.add(lines.get(line));
                    lines.remove(line);
                    offsetInLine = 0;
                    if (line > lines.size() - 1) {
                        line--;
                    }
                }
                display.clear();
                computeAllOffsets();
                dirty = true;
                uncut = false;
            }
        }

        void uncut() {
            if (cutbuffer.isEmpty()) {
                return;
            }
            String l = lines.get(line);
            int col = charPosition(offsetInLine + column);
            if (cut2end) {
                lines.set(line, l.substring(0, col) + cutbuffer.get(0) + l.substring(col));
                computeAllOffsets();
                moveRight(col + cutbuffer.get(0).length(), true);
            } else if (col == 0) {
                lines.addAll(line, cutbuffer);
                computeAllOffsets();
                if (cutbuffer.size() > 1) {
                    gotoLine(cutbuffer.get(cutbuffer.size() - 1).length(), line + cutbuffer.size());
                } else {
                    moveRight(cutbuffer.get(0).length(), true);
                }
            } else {
                int gotol = line;
                if (cutbuffer.size() == 1) {
                    lines.set(line, l.substring(0, col) + cutbuffer.get(0) + l.substring(col));
                } else {
                    lines.set(line++, l.substring(0, col) + cutbuffer.get(0));
                    gotol = line;
                    lines.add(line, cutbuffer.get(cutbuffer.size() - 1) + l.substring(col));
                    for (int i = cutbuffer.size() - 2; i > 0; i--) {
                        gotol++;
                        lines.add(line, cutbuffer.get(i));
                    }
                }
                computeAllOffsets();
                if (cutbuffer.size() > 1) {
                    gotoLine(cutbuffer.get(cutbuffer.size() - 1).length(), gotol);
                } else {
                    moveRight(col + cutbuffer.get(0).length(), true);
                }
            }
            display.clear();
            dirty = true;
            uncut = true;
        }

        void mark() {
            if (mark) {
                markPos[0] = line;
                markPos[1] = offsetInLine + column;
            } else {
                markPos[0] = -1;
                markPos[1] = -1;
            }
        }

        int[] getMarkStart() {
            int[] out = {-1, -1};
            if (!mark) {
                return out;
            }
            if (markPos[0] > line || (markPos[0] == line && markPos[1] > offsetInLine + column)) {
                out[0] = line;
                out[1] = offsetInLine + column;
            } else {
                out = markPos;
            }
            return out;
        }

        int[] getMarkEnd() {
            int[] out = {-1, -1};
            if (!mark) {
                return out;
            }
            if (markPos[0] > line || (markPos[0] == line && markPos[1] > offsetInLine + column)) {
                out = markPos;
            } else {
                out[0] = line;
                out[1] = offsetInLine + column;
            }
            return out;
        }

        void replaceFromCursor(int chars, String string) {
            int pos = charPosition(offsetInLine + column);
            String text = lines.get(line);
            String mod = text.substring(0, pos) + string;
            if (chars + pos < text.length()) {
                mod += text.substring(chars + pos);
            }
            lines.set(line, mod);
            dirty = true;
        }
    }

    protected static class PatternHistory {
        private final Path historyFile;
        private final int size = 100;
        private List<String> patterns = new ArrayList<>();
        private int patternId = -1;
        private boolean lastMoveUp = false;

        public PatternHistory(Path historyFile) {
            this.historyFile = historyFile;
            load();
        }

        public String up(String hint) {
            String out = hint;
            if (!patterns.isEmpty() && patternId < patterns.size()) {
                if (!lastMoveUp && patternId > 0 && patternId < patterns.size() - 1) {
                    patternId++;
                }
                if (patternId < 0) {
                    patternId = 0;
                }
                boolean found = false;
                for (int pid = patternId; pid < patterns.size(); pid++) {
                    if (hint.isEmpty() || patterns.get(pid).startsWith(hint)) {
                        patternId = pid + 1;
                        out = patterns.get(pid);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    patternId = patterns.size();
                }
            }
            lastMoveUp = true;
            return out;
        }

        public String down(String hint) {
            String out = hint;
            if (!patterns.isEmpty()) {
                if (lastMoveUp) {
                    patternId--;
                }
                if (patternId < 0) {
                    patternId = -1;
                } else {
                    boolean found = false;
                    for (int pid = patternId; pid >= 0; pid--) {
                        if (hint.isEmpty() || patterns.get(pid).startsWith(hint)) {
                            patternId = pid - 1;
                            out = patterns.get(pid);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        patternId = -1;
                    }
                }
            }
            lastMoveUp = false;
            return out;
        }

        public void add(String pattern) {
            if (pattern.trim().isEmpty()) {
                return;
            }
            patterns.remove(pattern);
            if (patterns.size() > size) {
                patterns.remove(patterns.size() - 1);
            }
            patterns.add(0, pattern);
            patternId = -1;
        }

        public void persist() {
            if (historyFile == null) {
                return;
            }
            try {
                try (BufferedWriter writer = Files.newBufferedWriter(
                        historyFile.toAbsolutePath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                    for (String s : patterns) {
                        if (!s.trim().isEmpty()) {
                            writer.append(s);
                            writer.newLine();
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }

        private void load() {
            if (historyFile == null) {
                return;
            }
            try {
                if (Files.exists(historyFile)) {
                    patterns = new ArrayList<>();
                    try (BufferedReader reader = Files.newBufferedReader(historyFile)) {
                        reader.lines().forEach(line -> patterns.add(line));
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public Nano(Terminal terminal, File root) {
        this(terminal, root.toPath());
    }

    public Nano(Terminal terminal, Path root) {
        this(terminal, root, null);
    }

    public Nano(Terminal terminal, Path root, Options opts) {
        this(terminal, root, opts, null);
    }

    @SuppressWarnings("this-escape")
    public Nano(Terminal terminal, Path root, Options opts, ConfigurationPath configPath) {
        this.terminal = terminal;
        this.windowsTerminal = terminal.getClass().getSimpleName().endsWith("WinSysTerminal");
        this.root = root;
        this.display = new Display(terminal, true);
        this.bindingReader = new BindingReader(terminal.reader());
        this.size = new Size();
        Attributes attrs = terminal.getAttributes();
        this.vsusp = attrs.getControlChar(ControlChar.VSUSP);
        if (vsusp > 0) {
            attrs.setControlChar(ControlChar.VSUSP, 0);
            terminal.setAttributes(attrs);
        }
        Path nanorc = configPath != null ? configPath.getConfig(DEFAULT_NANORC_FILE) : null;
        boolean ignorercfiles = opts != null && opts.isSet("ignorercfiles");
        if (nanorc != null && !ignorercfiles) {
            try {
                parseConfig(nanorc);
            } catch (IOException e) {
                errorMessage = "Encountered error while reading config file: " + nanorc;
            }
        } else if (new File("/usr/share/nano").exists() && !ignorercfiles) {
            PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:/usr/share/nano/*.nanorc");
            try (Stream<Path> pathStream = Files.walk(Paths.get("/usr/share/nano"))) {
                pathStream.filter(pathMatcher::matches).forEach(syntaxFiles::add);
                nanorcIgnoreErrors = true;
            } catch (IOException e) {
                errorMessage = "Encountered error while reading nanorc files";
            }
        }
        if (opts != null) {
            this.restricted = opts.isSet("restricted");
            this.syntaxName = null;
            if (opts.isSet("syntax")) {
                this.syntaxName = opts.get("syntax");
                nanorcIgnoreErrors = false;
            }
            if (opts.isSet("backup")) {
                writeBackup = true;
            }
            if (opts.isSet("quotestr")) {
                quoteStr = opts.get("quotestr");
            }
            if (opts.isSet("tabsize")) {
                tabs = opts.getNumber("tabsize");
            }
            if (opts.isSet("quickblank")) {
                quickBlank = true;
            }
            if (opts.isSet("constantshow")) {
                constantCursor = true;
            }
            if (opts.isSet("emptyline")) {
                oneMoreLine = false;
            }
            if (opts.isSet("jumpyscrolling")) {
                smoothScrolling = false;
            }
            if (opts.isSet("linenumbers")) {
                printLineNumbers = true;
            }
            if (opts.isSet("mouse")) {
                mouseSupport = true;
            }
            if (opts.isSet("softwrap")) {
                wrapping = true;
            }
            if (opts.isSet("atblanks")) {
                atBlanks = true;
            }
            if (opts.isSet("suspend")) {
                enableSuspension();
            }
            if (opts.isSet("view")) {
                view = true;
            }
            if (opts.isSet("cutfromcursor")) {
                cut2end = true;
            }
            if (opts.isSet("tempfile")) {
                tempFile = true;
            }
            if (opts.isSet("historylog")) {
                historyLog = opts.get("historyLog");
            }
            if (opts.isSet("tabstospaces")) {
                tabsToSpaces = true;
            }
            if (opts.isSet("autoindent")) {
                autoIndent = true;
            }
        }
        bindKeys();
        if (configPath != null && historyLog != null) {
            try {
                patternHistory = new PatternHistory(configPath.getUserConfig(historyLog, true));
            } catch (IOException e) {
                errorMessage = "Encountered error while reading pattern-history file: " + historyLog;
            }
        }
    }

    private void parseConfig(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    List<String> parts = SyntaxHighlighter.RuleSplitter.split(line);
                    if (parts.get(0).equals(COMMAND_INCLUDE)) {
                        SyntaxHighlighter.nanorcInclude(file, parts.get(1), syntaxFiles);
                    } else if (parts.get(0).equals(COMMAND_THEME)) {
                        SyntaxHighlighter.nanorcTheme(file, parts.get(1), syntaxFiles);
                    } else if (parts.size() == 2
                            && (parts.get(0).equals("set") || parts.get(0).equals("unset"))) {
                        String option = parts.get(1);
                        boolean val = parts.get(0).equals("set");
                        switch (option) {
                            case "linenumbers":
                                printLineNumbers = val;
                                break;
                            case "jumpyscrolling":
                                smoothScrolling = !val;
                                break;
                            case "smooth":
                                smoothScrolling = val;
                                break;
                            case "softwrap":
                                wrapping = val;
                                break;
                            case "mouse":
                                mouseSupport = val;
                                break;
                            case "emptyline":
                                oneMoreLine = val;
                                break;
                            case "morespace":
                                oneMoreLine = !val;
                                break;
                            case "constantshow":
                                constantCursor = val;
                                break;
                            case "quickblank":
                                quickBlank = val;
                                break;
                            case "atblanks":
                                atBlanks = val;
                                break;
                            case "suspend":
                                enableSuspension();
                                break;
                            case "view":
                                view = val;
                                break;
                            case "cutfromcursor":
                                cut2end = val;
                                break;
                            case "tempfile":
                                tempFile = val;
                                break;
                            case "tabstospaces":
                                tabsToSpaces = val;
                                break;
                            case "autoindent":
                                autoIndent = val;
                                break;
                            default:
                                errorMessage = "Nano config: Unknown or unsupported configuration option " + option;
                                break;
                        }
                    } else if (parts.size() == 3 && parts.get(0).equals("set")) {
                        String option = parts.get(1);
                        String val = parts.get(2);
                        switch (option) {
                            case "quotestr":
                                quoteStr = val;
                                break;
                            case "punct":
                                punct = val;
                                break;
                            case "matchbrackets":
                                matchBrackets = val;
                                break;
                            case "brackets":
                                brackets = val;
                                break;
                            case "historylog":
                                historyLog = val;
                                break;
                            default:
                                errorMessage = "Nano config: Unknown or unsupported configuration option " + option;
                                break;
                        }
                    } else if (parts.get(0).equals("bind") || parts.get(0).equals("unbind")) {
                        errorMessage = "Nano config: Key bindings can not be changed!";
                    } else {
                        errorMessage = "Nano config: Bad configuration '" + line + "'";
                    }
                }
            }
        }
    }

    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }

    public void open(String... files) throws IOException {
        open(Arrays.asList(files));
    }

    public void open(List<String> files) throws IOException {
        for (String file : files) {
            file = file.startsWith("~") ? file.replace("~", System.getProperty("user.home")) : file;
            if (file.contains("*") || file.contains("?")) {
                for (Path p : Commands.findFiles(root, file)) {
                    buffers.add(new Buffer(p.toString()));
                }
            } else {
                buffers.add(new Buffer(file));
            }
        }
    }

    public void run() throws IOException {
        if (buffers.isEmpty()) {
            buffers.add(new Buffer(null));
        }
        buffer = buffers.get(bufferIndex);

        Attributes attributes = terminal.getAttributes();
        Attributes newAttr = new Attributes(attributes);
        if (vsusp > 0) {
            attributes.setControlChar(ControlChar.VSUSP, vsusp);
        }
        newAttr.setLocalFlags(EnumSet.of(LocalFlag.ICANON, LocalFlag.ECHO, LocalFlag.IEXTEN, LocalFlag.ISIG), false);
        newAttr.setInputFlags(EnumSet.of(InputFlag.IXON, InputFlag.ICRNL, InputFlag.INLCR), false);
        newAttr.setControlChar(ControlChar.VMIN, 1);
        newAttr.setControlChar(ControlChar.VTIME, 0);
        newAttr.setControlChar(ControlChar.VINTR, 0);
        terminal.setAttributes(newAttr);
        terminal.puts(Capability.enter_ca_mode);
        terminal.puts(Capability.keypad_xmit);
        if (mouseSupport) {
            mouseTracking = terminal.getCurrentMouseTracking();
            // track buttons and mouse moves for tooltips
            terminal.trackMouse(Terminal.MouseTracking.Any);
        }

        this.shortcuts = standardShortcuts();

        SignalHandler prevHandler = null;
        Status status = Status.getStatus(terminal, false);
        try {
            size.copy(terminal.getSize());
            if (status != null) {
                status.suspend();
            }
            buffer.open();
            if (errorMessage != null) {
                setMessage(errorMessage);
                errorMessage = null;
            } else if (buffer.file != null) {
                setMessage("Read " + buffer.lines.size() + " lines");
            }

            display.clear();
            display.reset();
            display.resize(size.getRows(), size.getColumns());
            prevHandler = terminal.handle(Signal.WINCH, this::handle);

            display();

            while (true) {
                Operation op;
                switch (op = readOperation(keys)) {
                    case QUIT:
                        if (help) {
                            resetSuggestion();
                            break;
                        }
                        if (quit()) {
                            return;
                        }
                        break;
                    case WRITE:
                        write();
                        break;
                    case READ:
                        read();
                        break;
                    case UP:
                        if (help && suggestionBox != null) {
                            suggestionBox.up();
                        } else {
                            buffer.moveUp(1);
                        }
                        break;
                    case DOWN:
                        if (help && suggestionBox != null) {
                            suggestionBox.down();
                        } else {
                            buffer.moveDown(1);
                        }
                        break;
                    case LEFT:
                        buffer.moveLeft(1);
                        if (help) {
                            resetSuggestion();
                        }
                        break;
                    case RIGHT:
                        buffer.moveRight(1);
                        if (help) {
                            resetSuggestion();
                        }
                        break;
                    case INSERT:
                        if (this.help) {
                            this.insertHelp = true;
                        } else {
                            buffer.insert(bindingReader.getLastBinding());
                        }
                        break;
                    case BACKSPACE:
                        buffer.backspace(1);
                        break;
                    case DELETE:
                        buffer.delete(1);
                        break;
                    case WRAP:
                        wrap();
                        break;
                    case NUMBERS:
                        numbers();
                        break;
                    case SMOOTH_SCROLLING:
                        smoothScrolling();
                        break;
                    case MOUSE_SUPPORT:
                        mouseSupport();
                        break;
                    case ONE_MORE_LINE:
                        oneMoreLine();
                        break;
                    case CLEAR_SCREEN:
                        clearScreen();
                        break;
                    case PREV_BUFFER:
                        prevBuffer();
                        break;
                    case NEXT_BUFFER:
                        nextBuffer();
                        break;
                    case CUR_POS:
                        curPos();
                        break;
                    case LSP_SUGGESTION:
                        help = true;
                        break;
                    case BEGINNING_OF_LINE:
                        buffer.beginningOfLine();
                        break;
                    case END_OF_LINE:
                        buffer.endOfLine();
                        break;
                    case FIRST_LINE:
                        buffer.firstLine();
                        break;
                    case LAST_LINE:
                        buffer.lastLine();
                        break;
                    case PREV_PAGE:
                        buffer.prevPage();
                        break;
                    case NEXT_PAGE:
                        buffer.nextPage();
                        break;
                    case SCROLL_UP:
                        buffer.scrollUp(1);
                        break;
                    case SCROLL_DOWN:
                        buffer.scrollDown(1);
                        break;
                    case SEARCH:
                        searchToReplace = false;
                        searchAndReplace();
                        break;
                    case REPLACE:
                        searchToReplace = true;
                        searchAndReplace();
                        break;
                    case NEXT_SEARCH:
                        buffer.nextSearch();
                        break;
                    case HELP:
                        help("nano-main-help.txt");
                        break;
                    case CONSTANT_CURSOR:
                        constantCursor();
                        break;
                    case VERBATIM:
                        buffer.insert(new String(Character.toChars(bindingReader.readCharacter())));
                        break;
                    case MATCHING:
                        buffer.matching();
                        break;
                    case MOUSE_EVENT:
                        mouseEvent();
                        break;
                    case TOGGLE_SUSPENSION:
                        toggleSuspension();
                        break;
                    case COPY:
                        buffer.copy();
                        break;
                    case CUT:
                        buffer.cut();
                        break;
                    case UNCUT:
                        buffer.uncut();
                        break;
                    case GOTO:
                        gotoLine();
                        curPos();
                        break;
                    case CUT_TO_END_TOGGLE:
                        cut2end = !cut2end;
                        setMessage("Cut to end " + (cut2end ? "enabled" : "disabled"));
                        break;
                    case CUT_TO_END:
                        buffer.cut(true);
                        break;
                    case MARK:
                        mark = !mark;
                        setMessage("Mark " + (mark ? "Set" : "Unset"));
                        buffer.mark();
                        break;
                    case HIGHLIGHT:
                        highlight = !highlight;
                        setMessage("Highlight " + (highlight ? "enabled" : "disabled"));
                        break;
                    case TABS_TO_SPACE:
                        tabsToSpaces = !tabsToSpaces;
                        setMessage("Conversion of typed tabs to spaces " + (tabsToSpaces ? "enabled" : "disabled"));
                        break;
                    case AUTO_INDENT:
                        autoIndent = !autoIndent;
                        setMessage("Auto indent " + (autoIndent ? "enabled" : "disabled"));
                        break;
                    default:
                        setMessage("Unsupported " + op.name().toLowerCase().replace('_', '-'));
                        break;
                }
                display();
            }
        } finally {
            if (mouseSupport) {
                // Restore previous mouse tracking mode
                terminal.trackMouse(mouseTracking);
            }
            if (!terminal.puts(Capability.exit_ca_mode)) {
                terminal.puts(Capability.clear_screen);
            }
            terminal.puts(Capability.keypad_local);
            terminal.flush();
            terminal.setAttributes(attributes);
            terminal.handle(Signal.WINCH, prevHandler);
            if (status != null) {
                status.restore();
            }
            patternHistory.persist();
        }
    }

    private void resetSuggestion() {
        this.suggestions = null;
        this.suggestionBox = null;
        this.insertHelp = false;
        this.help = false;
    }

    private int editInputBuffer(Operation operation, int curPos) {
        switch (operation) {
            case INSERT:
                editBuffer.insert(curPos++, bindingReader.getLastBinding());
                break;
            case BACKSPACE:
                if (curPos > 0) {
                    editBuffer.deleteCharAt(--curPos);
                }
                break;
            case LEFT:
                if (curPos > 0) {
                    curPos--;
                }
                break;
            case RIGHT:
                if (curPos < editBuffer.length()) {
                    curPos++;
                }
                break;
        }
        return curPos;
    }

    boolean write() throws IOException {
        KeyMap<Operation> writeKeyMap = new KeyMap<>();
        if (!restricted) {
            writeKeyMap.setUnicode(Operation.INSERT);
            for (char i = 32; i < 256; i++) {
                writeKeyMap.bind(Operation.INSERT, Character.toString(i));
            }
            for (char i = 'A'; i <= 'Z'; i++) {
                writeKeyMap.bind(Operation.DO_LOWER_CASE, alt(i));
            }
            writeKeyMap.bind(Operation.BACKSPACE, del());
            writeKeyMap.bind(Operation.APPEND_MODE, alt('a'));
            writeKeyMap.bind(Operation.PREPEND_MODE, alt('p'));
            writeKeyMap.bind(Operation.BACKUP, alt('b'));
            writeKeyMap.bind(Operation.TO_FILES, ctrl('T'));
        }
        writeKeyMap.bind(Operation.MAC_FORMAT, alt('m'));
        writeKeyMap.bind(Operation.DOS_FORMAT, alt('d'));
        writeKeyMap.bind(Operation.ACCEPT, "\r");
        writeKeyMap.bind(Operation.CANCEL, ctrl('C'));
        writeKeyMap.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));

        // Bind all possible mouse event prefixes
        // This ensures mouse events are recognized regardless of the terminal's kmous capability
        writeKeyMap.bind(Operation.MOUSE_EVENT, MouseSupport.keys(terminal));

        writeKeyMap.bind(Operation.TOGGLE_SUSPENSION, alt('z'));
        writeKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        writeKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left));

        editMessage = getWriteMessage();
        editBuffer.setLength(0);
        editBuffer.append(buffer.file == null ? "" : buffer.file);
        int curPos = editBuffer.length();
        this.shortcuts = writeShortcuts();
        display(curPos);
        while (true) {
            Operation op = readOperation(writeKeyMap);
            switch (op) {
                case CANCEL:
                    editMessage = null;
                    this.shortcuts = standardShortcuts();
                    return false;
                case ACCEPT:
                    editMessage = null;
                    if (save(editBuffer.toString())) {
                        this.shortcuts = standardShortcuts();
                        return true;
                    }
                    return false;
                case HELP:
                    help("nano-write-help.txt");
                    break;
                case MAC_FORMAT:
                    buffer.format = (buffer.format == WriteFormat.MAC) ? WriteFormat.UNIX : WriteFormat.MAC;
                    break;
                case DOS_FORMAT:
                    buffer.format = (buffer.format == WriteFormat.DOS) ? WriteFormat.UNIX : WriteFormat.DOS;
                    break;
                case APPEND_MODE:
                    writeMode = (writeMode == WriteMode.APPEND) ? WriteMode.WRITE : WriteMode.APPEND;
                    break;
                case PREPEND_MODE:
                    writeMode = (writeMode == WriteMode.PREPEND) ? WriteMode.WRITE : WriteMode.PREPEND;
                    break;
                case BACKUP:
                    writeBackup = !writeBackup;
                    break;
                case MOUSE_EVENT:
                    mouseEvent();
                    break;
                case TOGGLE_SUSPENSION:
                    toggleSuspension();
                    break;
                default:
                    curPos = editInputBuffer(op, curPos);
                    break;
            }
            editMessage = getWriteMessage();
            display(curPos);
        }
    }

    private Operation readOperation(KeyMap<Operation> keymap) {
        while (true) {
            Operation op = bindingReader.readBinding(keymap);
            if (op == Operation.DO_LOWER_CASE) {
                bindingReader.runMacro(bindingReader.getLastBinding().toLowerCase());
            } else {
                return op;
            }
        }
    }

    private boolean save(String name) throws IOException {
        Path orgPath = buffer.file != null ? root.resolve(buffer.file) : null;
        Path newPath = root.resolve(name);
        boolean isSame =
                orgPath != null && Files.exists(orgPath) && Files.exists(newPath) && Files.isSameFile(orgPath, newPath);
        if (!isSame && Files.exists(Paths.get(name)) && writeMode == WriteMode.WRITE) {
            Operation op = getYNC("File exists, OVERWRITE ? ");
            if (op != Operation.YES) {
                return false;
            }
        } else if (!Files.exists(newPath)) {
            Files.createFile(newPath);
        }
        Path t = Files.createTempFile("jline-", ".temp");
        try (OutputStream os = Files.newOutputStream(
                t, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            if (writeMode == WriteMode.APPEND) {
                if (Files.isReadable(newPath)) {
                    Files.copy(newPath, os);
                }
            }
            Writer w = new OutputStreamWriter(os, buffer.charset);
            for (int i = 0; i < buffer.lines.size(); i++) {
                w.write(buffer.lines.get(i));
                switch (buffer.format) {
                    case UNIX:
                        w.write("\n");
                        break;
                    case DOS:
                        w.write("\r\n");
                        break;
                    case MAC:
                        w.write("\r");
                        break;
                }
            }
            w.flush();
            if (writeMode == WriteMode.PREPEND) {
                if (Files.isReadable(newPath)) {
                    Files.copy(newPath, os);
                }
            }
            if (writeBackup) {
                Files.move(
                        newPath,
                        newPath.resolveSibling(newPath.getFileName().toString() + "~"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(t, newPath, StandardCopyOption.REPLACE_EXISTING);
            if (writeMode == WriteMode.WRITE) {
                buffer.file = name;
                buffer.dirty = false;
            }
            setMessage("Wrote " + buffer.lines.size() + " lines");
            return true;
        } catch (IOException e) {
            setMessage("Error writing " + name + ": " + e);
            return false;
        } finally {
            Files.deleteIfExists(t);
            writeMode = WriteMode.WRITE;
        }
    }

    private Operation getYNC(String message) {
        return getYNC(message, false);
    }

    private Operation getYNC(String message, boolean andAll) {
        String oldEditMessage = editMessage;
        String oldEditBuffer = editBuffer.toString();
        LinkedHashMap<String, String> oldShortcuts = shortcuts;
        try {
            editMessage = message;
            editBuffer.setLength(0);
            KeyMap<Operation> yncKeyMap = new KeyMap<>();
            yncKeyMap.bind(Operation.YES, "y", "Y");
            if (andAll) {
                yncKeyMap.bind(Operation.ALL, "a", "A");
            }
            yncKeyMap.bind(Operation.NO, "n", "N");
            yncKeyMap.bind(Operation.CANCEL, ctrl('C'));
            shortcuts = new LinkedHashMap<>();
            shortcuts.put(" Y", "Yes");
            if (andAll) {
                shortcuts.put(" A", "All");
            }
            shortcuts.put(" N", "No");
            shortcuts.put("^C", "Cancel");
            display();
            return readOperation(yncKeyMap);
        } finally {
            editMessage = oldEditMessage;
            editBuffer.append(oldEditBuffer);
            shortcuts = oldShortcuts;
        }
    }

    private String getWriteMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("File Name to ");
        switch (writeMode) {
            case WRITE:
                sb.append("Write");
                break;
            case APPEND:
                sb.append("Append");
                break;
            case PREPEND:
                sb.append("Prepend");
                break;
        }
        switch (buffer.format) {
            case UNIX:
                break;
            case DOS:
                sb.append(" [DOS Format]");
                break;
            case MAC:
                sb.append(" [Mac Format]");
                break;
        }
        if (writeBackup) {
            sb.append(" [Backup]");
        }
        sb.append(": ");
        return sb.toString();
    }

    void read() {
        KeyMap<Operation> readKeyMap = new KeyMap<>();
        readKeyMap.setUnicode(Operation.INSERT);
        for (char i = 32; i < 256; i++) {
            readKeyMap.bind(Operation.INSERT, Character.toString(i));
        }
        for (char i = 'A'; i <= 'Z'; i++) {
            readKeyMap.bind(Operation.DO_LOWER_CASE, alt(i));
        }
        readKeyMap.bind(Operation.BACKSPACE, del());
        readKeyMap.bind(Operation.NEW_BUFFER, alt('f'));
        readKeyMap.bind(Operation.TO_FILES, ctrl('T'));
        readKeyMap.bind(Operation.EXECUTE, ctrl('X'));
        readKeyMap.bind(Operation.ACCEPT, "\r");
        readKeyMap.bind(Operation.CANCEL, ctrl('C'));
        readKeyMap.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));

        // Bind all possible mouse event prefixes
        // This ensures mouse events are recognized regardless of the terminal's kmous capability
        readKeyMap.bind(Operation.MOUSE_EVENT, MouseSupport.keys(terminal));

        readKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        readKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left));

        editMessage = getReadMessage();
        editBuffer.setLength(0);
        int curPos = editBuffer.length();
        this.shortcuts = readShortcuts();
        display(curPos);
        while (true) {
            Operation op = readOperation(readKeyMap);
            switch (op) {
                case CANCEL:
                    editMessage = null;
                    this.shortcuts = standardShortcuts();
                    return;
                case ACCEPT:
                    editMessage = null;
                    String file = editBuffer.toString();
                    boolean empty = file.isEmpty();
                    Path p = empty ? null : root.resolve(file);
                    if (!readNewBuffer && !empty && !Files.exists(p)) {
                        setMessage("\"" + file + "\" not found");
                    } else if (!empty && Files.isDirectory(p)) {
                        setMessage("\"" + file + "\" is a directory");
                    } else if (!empty && !Files.isRegularFile(p)) {
                        setMessage("\"" + file + "\" is not a regular file");
                    } else {
                        Buffer buf = new Buffer(empty ? null : file);
                        try {
                            buf.open();
                            if (readNewBuffer) {
                                buffers.add(++bufferIndex, buf);
                                buffer = buf;
                            } else {
                                buffer.insert(String.join("\n", buf.lines));
                            }
                            setMessage(null);
                        } catch (IOException e) {
                            setMessage("Error reading " + file + ": " + e.getMessage());
                        }
                    }
                    this.shortcuts = standardShortcuts();
                    return;
                case HELP:
                    help("nano-read-help.txt");
                    break;
                case NEW_BUFFER:
                    readNewBuffer = !readNewBuffer;
                    break;
                case MOUSE_EVENT:
                    mouseEvent();
                    break;
                default:
                    curPos = editInputBuffer(op, curPos);
                    break;
            }
            editMessage = getReadMessage();
            display(curPos);
        }
    }

    private String getReadMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("File to insert");
        if (readNewBuffer) {
            sb.append(" into new buffer");
        }
        sb.append(" [from ./]: ");
        return sb.toString();
    }

    void gotoLine() {
        KeyMap<Operation> readKeyMap = new KeyMap<>();
        readKeyMap.setUnicode(Operation.INSERT);
        for (char i = 32; i < 256; i++) {
            readKeyMap.bind(Operation.INSERT, Character.toString(i));
        }
        readKeyMap.bind(Operation.BACKSPACE, del());
        readKeyMap.bind(Operation.ACCEPT, "\r");
        readKeyMap.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));
        readKeyMap.bind(Operation.CANCEL, ctrl('C'));

        // Bind all possible mouse event prefixes
        // This ensures mouse events are recognized regardless of the terminal's kmous capability
        readKeyMap.bind(Operation.MOUSE_EVENT, MouseSupport.keys(terminal));

        readKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        readKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left));
        readKeyMap.bind(Operation.FIRST_LINE, ctrl('Y'));
        readKeyMap.bind(Operation.LAST_LINE, ctrl('V'));
        readKeyMap.bind(Operation.SEARCH, ctrl('T'));

        editMessage = "Enter line number, column number: ";
        editBuffer.setLength(0);
        int curPos = editBuffer.length();
        this.shortcuts = gotoShortcuts();
        display(curPos);
        while (true) {
            Operation op = readOperation(readKeyMap);
            switch (op) {
                case CANCEL:
                    editMessage = null;
                    this.shortcuts = standardShortcuts();
                    return;
                case FIRST_LINE:
                    editMessage = null;
                    buffer.firstLine();
                    this.shortcuts = standardShortcuts();
                    return;
                case LAST_LINE:
                    editMessage = null;
                    buffer.lastLine();
                    this.shortcuts = standardShortcuts();
                    return;
                case SEARCH:
                    searchToReplace = false;
                    searchAndReplace();
                    return;
                case ACCEPT:
                    editMessage = null;
                    String[] pos = editBuffer.toString().split(",", 2);
                    int[] args = {0, 0};
                    try {
                        for (int i = 0; i < pos.length; i++) {
                            if (!pos[i].trim().isEmpty()) {
                                args[i] = Integer.parseInt(pos[i]) - 1;
                                if (args[i] < 0) {
                                    throw new NumberFormatException();
                                }
                            }
                        }
                        buffer.gotoLine(args[1], args[0]);
                    } catch (NumberFormatException ex) {
                        setMessage("Invalid line or column number");
                    } catch (Exception ex) {
                        setMessage("Internal error: " + ex.getMessage());
                    }
                    this.shortcuts = standardShortcuts();
                    return;
                case HELP:
                    help("nano-goto-help.txt");
                    break;
                default:
                    curPos = editInputBuffer(op, curPos);
                    break;
            }
            display(curPos);
        }
    }

    private LinkedHashMap<String, String> gotoShortcuts() {
        LinkedHashMap<String, String> shortcuts = new LinkedHashMap<>();
        shortcuts.put("^G", "Get Help");
        shortcuts.put("^Y", "First Line");
        shortcuts.put("^T", "Go To Text");
        shortcuts.put("^C", "Cancel");
        shortcuts.put("^V", "Last Line");
        return shortcuts;
    }

    private LinkedHashMap<String, String> readShortcuts() {
        LinkedHashMap<String, String> shortcuts = new LinkedHashMap<>();
        shortcuts.put("^G", "Get Help");
        shortcuts.put("^T", "To Files");
        shortcuts.put("M-F", "New Buffer");
        shortcuts.put("^C", "Cancel");
        shortcuts.put("^X", "Execute Command");
        return shortcuts;
    }

    private LinkedHashMap<String, String> writeShortcuts() {
        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("^G", "Get Help");
        s.put("M-M", "Mac Format");
        s.put("^C", "Cancel");
        s.put("M-D", "DOS Format");
        if (!restricted) {
            s.put("^T", "To Files");
            s.put("M-P", "Prepend");
            s.put("M-A", "Append");
            s.put("M-B", "Backup File");
        }
        return s;
    }

    private LinkedHashMap<String, String> helpShortcuts() {
        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("^L", "Refresh");
        s.put("^Y", "Prev Page");
        s.put("^P", "Prev Line");
        s.put("M-\\", "First Line");
        s.put("^X", "Exit");
        s.put("^V", "Next Page");
        s.put("^N", "Next Line");
        s.put("M-/", "Last Line");
        return s;
    }

    private LinkedHashMap<String, String> searchShortcuts() {
        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("^G", "Get Help");
        s.put("^Y", "First Line");
        if (searchToReplace) {
            s.put("^R", "No Replace");
        } else {
            s.put("^R", "Replace");
            s.put("^W", "Beg of Par");
        }
        s.put("M-C", "Case Sens");
        s.put("M-R", "Regexp");
        s.put("^C", "Cancel");
        s.put("^V", "Last Line");
        s.put("^T", "Go To Line");
        if (!searchToReplace) {
            s.put("^O", "End of Par");
        }
        s.put("M-B", "Backwards");
        s.put("^P", "PrevHstory");
        return s;
    }

    private LinkedHashMap<String, String> replaceShortcuts() {
        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("^G", "Get Help");
        s.put("^Y", "First Line");
        s.put("^P", "PrevHstory");
        s.put("^C", "Cancel");
        s.put("^V", "Last Line");
        s.put("^N", "NextHstory");
        return s;
    }

    private LinkedHashMap<String, String> standardShortcuts() {
        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("^G", "Get Help");
        if (!view) {
            s.put("^O", "WriteOut");
        }
        s.put("^R", "Read File");
        s.put("^Y", "Prev Page");
        if (!view) {
            s.put("^K", "Cut Text");
        }
        s.put("^C", "Cur Pos");
        s.put("^X", "Exit");
        if (!view) {
            s.put("^J", "Justify");
        }
        s.put("^W", "Where Is");
        s.put("^V", "Next Page");
        if (!view) {
            s.put("^U", "UnCut Text");
        }
        s.put("^T", "To Spell");
        return s;
    }

    void help(String help) {
        Buffer org = this.buffer;
        Buffer newBuf = new Buffer(null);
        try (InputStream is = getClass().getResourceAsStream(help)) {
            newBuf.open(is);
        } catch (IOException e) {
            setMessage("Unable to read help");
            return;
        }
        LinkedHashMap<String, String> oldShortcuts = this.shortcuts;
        this.shortcuts = helpShortcuts();
        boolean oldWrapping = this.wrapping;
        boolean oldPrintLineNumbers = this.printLineNumbers;
        boolean oldConstantCursor = this.constantCursor;
        boolean oldAtBlanks = this.atBlanks;
        boolean oldHighlight = this.highlight;
        String oldEditMessage = this.editMessage;
        this.editMessage = "";
        this.wrapping = true;
        this.atBlanks = true;
        this.printLineNumbers = false;
        this.constantCursor = false;
        this.highlight = false;
        this.buffer = newBuf;
        if (!oldWrapping) {
            buffer.computeAllOffsets();
        }
        try {
            this.message = null;
            terminal.puts(Capability.cursor_invisible);
            display();
            while (true) {
                switch (readOperation(keys)) {
                    case QUIT:
                        return;
                    case FIRST_LINE:
                        buffer.firstLine();
                        break;
                    case LAST_LINE:
                        buffer.lastLine();
                        break;
                    case PREV_PAGE:
                        buffer.prevPage();
                        break;
                    case NEXT_PAGE:
                        buffer.nextPage();
                        break;
                    case UP:
                        buffer.scrollUp(1);
                        break;
                    case DOWN:
                        buffer.scrollDown(1);
                        break;
                    case CLEAR_SCREEN:
                        clearScreen();
                        break;
                    case MOUSE_EVENT:
                        mouseEvent();
                        break;
                    case TOGGLE_SUSPENSION:
                        toggleSuspension();
                        break;
                }
                display();
            }
        } finally {
            this.buffer = org;
            this.wrapping = oldWrapping;
            this.printLineNumbers = oldPrintLineNumbers;
            this.constantCursor = oldConstantCursor;
            this.shortcuts = oldShortcuts;
            this.atBlanks = oldAtBlanks;
            this.highlight = oldHighlight;
            this.editMessage = oldEditMessage;
            terminal.puts(Capability.cursor_visible);
            if (!oldWrapping) {
                buffer.computeAllOffsets();
            }
        }
    }

    void searchAndReplace() {
        try {
            search();
            if (!searchToReplace) {
                return;
            }
            String replaceTerm = replace();
            int replaced = 0;
            boolean all = false;
            boolean found = true;
            List<Integer> matches = new ArrayList<>();
            Operation op = Operation.NO;
            while (found) {
                found = buffer.nextSearch();
                if (found) {
                    int[] re = buffer.highlightStart();
                    int col = searchBackwards ? buffer.length(buffer.getLine(re[0])) - re[1] : re[1];
                    int match = re[0] * 10000 + col;
                    if (matches.contains(match)) {
                        break;
                    } else {
                        matches.add(match);
                    }
                    if (!all) {
                        op = getYNC("Replace this instance? ", true);
                    }
                } else {
                    op = Operation.NO;
                }
                switch (op) {
                    case ALL:
                        all = true;
                        buffer.replaceFromCursor(matchedLength, replaceTerm);
                        replaced++;
                        break;
                    case YES:
                        buffer.replaceFromCursor(matchedLength, replaceTerm);
                        replaced++;
                        break;
                    case CANCEL:
                        found = false;
                        break;
                    case NO:
                    default:
                        break;
                }
            }
            message = "Replaced " + replaced + " occurrences";
        } catch (Exception e) {
            // ignore
        } finally {
            searchToReplace = false;
            matchedLength = -1;
            this.shortcuts = standardShortcuts();
            editMessage = null;
        }
    }

    void search() throws IOException {
        KeyMap<Operation> searchKeyMap = new KeyMap<>();
        searchKeyMap.setUnicode(Operation.INSERT);
        //        searchKeyMap.setNomatch(Operation.INSERT);
        for (char i = 32; i < 256; i++) {
            searchKeyMap.bind(Operation.INSERT, Character.toString(i));
        }
        for (char i = 'A'; i <= 'Z'; i++) {
            searchKeyMap.bind(Operation.DO_LOWER_CASE, alt(i));
        }
        searchKeyMap.bind(Operation.BACKSPACE, del());
        searchKeyMap.bind(Operation.CASE_SENSITIVE, alt('c'));
        searchKeyMap.bind(Operation.BACKWARDS, alt('b'));
        searchKeyMap.bind(Operation.REGEXP, alt('r'));
        searchKeyMap.bind(Operation.ACCEPT, "\r");
        searchKeyMap.bind(Operation.CANCEL, ctrl('C'));
        searchKeyMap.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));
        searchKeyMap.bind(Operation.FIRST_LINE, ctrl('Y'));
        searchKeyMap.bind(Operation.LAST_LINE, ctrl('V'));

        // Bind all possible mouse event prefixes
        // This ensures mouse events are recognized regardless of the terminal's kmous capability
        searchKeyMap.bind(Operation.MOUSE_EVENT, MouseSupport.keys(terminal));

        searchKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        searchKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left));
        searchKeyMap.bind(Operation.UP, key(terminal, Capability.key_up));
        searchKeyMap.bind(Operation.DOWN, key(terminal, Capability.key_down));
        searchKeyMap.bind(Operation.TOGGLE_REPLACE, ctrl('R'));

        editMessage = getSearchMessage();
        editBuffer.setLength(0);
        String currentBuffer = editBuffer.toString();
        int curPos = editBuffer.length();
        this.shortcuts = searchShortcuts();
        display(curPos);
        try {
            while (true) {
                Operation op = readOperation(searchKeyMap);
                switch (op) {
                    case UP:
                        editBuffer.setLength(0);
                        editBuffer.append(patternHistory.up(currentBuffer));
                        curPos = editBuffer.length();
                        break;
                    case DOWN:
                        editBuffer.setLength(0);
                        editBuffer.append(patternHistory.down(currentBuffer));
                        curPos = editBuffer.length();
                        break;
                    case CASE_SENSITIVE:
                        searchCaseSensitive = !searchCaseSensitive;
                        break;
                    case BACKWARDS:
                        searchBackwards = !searchBackwards;
                        break;
                    case REGEXP:
                        searchRegexp = !searchRegexp;
                        break;
                    case CANCEL:
                        throw new IllegalArgumentException();
                    case ACCEPT:
                        if (editBuffer.length() > 0) {
                            searchTerm = editBuffer.toString();
                        }
                        if (searchTerm == null || searchTerm.isEmpty()) {
                            setMessage("Cancelled");
                            throw new IllegalArgumentException();
                        } else {
                            patternHistory.add(searchTerm);
                            if (!searchToReplace) {
                                buffer.nextSearch();
                            }
                        }
                        return;
                    case HELP:
                        if (searchToReplace) {
                            help("nano-search-replace-help.txt");
                        } else {
                            help("nano-search-help.txt");
                        }
                        break;
                    case FIRST_LINE:
                        buffer.firstLine();
                        break;
                    case LAST_LINE:
                        buffer.lastLine();
                        break;
                    case MOUSE_EVENT:
                        mouseEvent();
                        break;
                    case TOGGLE_REPLACE:
                        searchToReplace = !searchToReplace;
                        this.shortcuts = searchShortcuts();
                        break;
                    default:
                        curPos = editInputBuffer(op, curPos);
                        currentBuffer = editBuffer.toString();
                        break;
                }
                editMessage = getSearchMessage();
                display(curPos);
            }
        } finally {
            this.shortcuts = standardShortcuts();
            editMessage = null;
        }
    }

    String replace() {
        KeyMap<Operation> keyMap = new KeyMap<>();
        keyMap.setUnicode(Operation.INSERT);
        //        keyMap.setNomatch(Operation.INSERT);
        for (char i = 32; i < 256; i++) {
            keyMap.bind(Operation.INSERT, Character.toString(i));
        }
        for (char i = 'A'; i <= 'Z'; i++) {
            keyMap.bind(Operation.DO_LOWER_CASE, alt(i));
        }
        keyMap.bind(Operation.BACKSPACE, del());
        keyMap.bind(Operation.ACCEPT, "\r");
        keyMap.bind(Operation.CANCEL, ctrl('C'));
        keyMap.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));
        keyMap.bind(Operation.FIRST_LINE, ctrl('Y'));
        keyMap.bind(Operation.LAST_LINE, ctrl('V'));

        // Bind all possible mouse event prefixes
        // This ensures mouse events are recognized regardless of the terminal's kmous capability
        keyMap.bind(Operation.MOUSE_EVENT, MouseSupport.keys(terminal));

        keyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        keyMap.bind(Operation.LEFT, key(terminal, Capability.key_left));
        keyMap.bind(Operation.UP, key(terminal, Capability.key_up));
        keyMap.bind(Operation.DOWN, key(terminal, Capability.key_down));

        editMessage = "Replace with: ";
        editBuffer.setLength(0);
        String currentBuffer = editBuffer.toString();
        int curPos = editBuffer.length();
        this.shortcuts = replaceShortcuts();
        display(curPos);
        try {
            while (true) {
                Operation op = readOperation(keyMap);
                switch (op) {
                    case UP:
                        editBuffer.setLength(0);
                        editBuffer.append(patternHistory.up(currentBuffer));
                        curPos = editBuffer.length();
                        break;
                    case DOWN:
                        editBuffer.setLength(0);
                        editBuffer.append(patternHistory.down(currentBuffer));
                        curPos = editBuffer.length();
                        break;
                    case CANCEL:
                        throw new IllegalArgumentException();
                    case ACCEPT:
                        String replaceTerm = "";
                        if (editBuffer.length() > 0) {
                            replaceTerm = editBuffer.toString();
                        }
                        patternHistory.add(replaceTerm);
                        return replaceTerm;
                    case HELP:
                        help("nano-replace-help.txt");
                        break;
                    case FIRST_LINE:
                        buffer.firstLine();
                        break;
                    case LAST_LINE:
                        buffer.lastLine();
                        break;
                    case MOUSE_EVENT:
                        mouseEvent();
                        break;
                    default:
                        curPos = editInputBuffer(op, curPos);
                        currentBuffer = editBuffer.toString();
                        break;
                }
                display(curPos);
            }
        } finally {
            this.shortcuts = standardShortcuts();
            editMessage = null;
        }
    }

    private String getSearchMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Search");
        if (searchToReplace) {
            sb.append(" (to replace)");
        }
        if (searchCaseSensitive) {
            sb.append(" [Case Sensitive]");
        }
        if (searchRegexp) {
            sb.append(" [Regexp]");
        }
        if (searchBackwards) {
            sb.append(" [Backwards]");
        }
        if (searchTerm != null) {
            sb.append(" [");
            sb.append(searchTerm);
            sb.append("]");
        }
        sb.append(": ");
        return sb.toString();
    }

    String computeCurPos() {
        int chari = 0;
        int chart = 0;
        for (int i = 0; i < buffer.lines.size(); i++) {
            int l = buffer.lines.get(i).length() + 1;
            if (i < buffer.line) {
                chari += l;
            } else if (i == buffer.line) {
                chari += buffer.offsetInLine + buffer.column;
            }
            chart += l;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("line ");
        sb.append(buffer.line + 1);
        sb.append("/");
        sb.append(buffer.lines.size());
        sb.append(" (");
        sb.append(Math.round((100.0 * buffer.line) / buffer.lines.size()));
        sb.append("%), ");
        sb.append("col ");
        sb.append(buffer.offsetInLine + buffer.column + 1);
        sb.append("/");
        sb.append(buffer.length(buffer.lines.get(buffer.line)) + 1);
        sb.append(" (");
        if (!buffer.lines.get(buffer.line).isEmpty()) {
            sb.append(Math.round(
                    (100.0 * (buffer.offsetInLine + buffer.column)) / (buffer.length(buffer.lines.get(buffer.line)))));
        } else {
            sb.append("100");
        }
        sb.append("%), ");
        sb.append("char ");
        sb.append(chari + 1);
        sb.append("/");
        sb.append(chart);
        sb.append(" (");
        sb.append(Math.round((100.0 * chari) / chart));
        sb.append("%)");
        return sb.toString();
    }

    void curPos() {
        setMessage(computeCurPos());
    }

    void prevBuffer() throws IOException {
        if (buffers.size() > 1) {
            bufferIndex = (bufferIndex + buffers.size() - 1) % buffers.size();
            buffer = buffers.get(bufferIndex);
            setMessage("Switched to " + buffer.getTitle());
            buffer.open();
            display.clear();
        } else {
            setMessage("No more open file buffers");
        }
    }

    void nextBuffer() throws IOException {
        if (buffers.size() > 1) {
            bufferIndex = (bufferIndex + 1) % buffers.size();
            buffer = buffers.get(bufferIndex);
            setMessage("Switched to " + buffer.getTitle());
            buffer.open();
            display.clear();
        } else {
            setMessage("No more open file buffers");
        }
    }

    void setMessage(String message) {
        this.message = message;
        this.nbBindings = quickBlank ? 2 : 25;
    }

    boolean quit() throws IOException {
        if (buffer.dirty) {
            if (tempFile) {
                if (!write()) {
                    return false;
                }
            } else {
                Operation op = getYNC("Save modified buffer (ANSWERING \"No\" WILL DESTROY CHANGES) ? ");
                switch (op) {
                    case CANCEL:
                        return false;
                    case NO:
                        break;
                    case YES:
                        if (!write()) {
                            return false;
                        }
                }
            }
        }
        buffers.remove(bufferIndex);
        if (bufferIndex == buffers.size() && bufferIndex > 0) {
            bufferIndex = buffers.size() - 1;
        }
        if (buffers.isEmpty()) {
            buffer = null;
            return true;
        } else {
            buffer = buffers.get(bufferIndex);
            buffer.open();
            display.clear();
            setMessage("Switched to " + buffer.getTitle());
            return false;
        }
    }

    void numbers() {
        printLineNumbers = !printLineNumbers;
        resetDisplay();
        setMessage("Lines numbering " + (printLineNumbers ? "enabled" : "disabled"));
    }

    void smoothScrolling() {
        smoothScrolling = !smoothScrolling;
        setMessage("Smooth scrolling " + (smoothScrolling ? "enabled" : "disabled"));
    }

    void mouseSupport() {
        mouseSupport = !mouseSupport;
        setMessage("Mouse support " + (mouseSupport ? "enabled" : "disabled"));
        // Use Normal tracking which includes button presses and releases
        terminal.trackMouse(mouseSupport ? Terminal.MouseTracking.Normal : Terminal.MouseTracking.Off);
    }

    void constantCursor() {
        constantCursor = !constantCursor;
        setMessage("Constant cursor position display " + (constantCursor ? "enabled" : "disabled"));
    }

    void oneMoreLine() {
        oneMoreLine = !oneMoreLine;
        setMessage("Use of one more line for editing " + (oneMoreLine ? "enabled" : "disabled"));
    }

    void wrap() {
        wrapping = !wrapping;
        buffer.computeAllOffsets();
        resetDisplay();
        setMessage("Lines wrapping " + (wrapping ? "enabled" : "disabled"));
    }

    void clearScreen() {
        resetDisplay();
    }

    void mouseEvent() {
        MouseEvent event = terminal.readMouseEvent(bindingReader::readCharacter, bindingReader.getLastBinding());
        // Only process mouse events if mouse support is enabled
        if (!mouseSupport) {
            return;
        }
        // Handle only specific mouse event types and ignore others
        MouseEvent.Type eventType = event.getType();
        if (eventType == MouseEvent.Type.Released
                && event.getModifiers().isEmpty()
                && event.getButton() == MouseEvent.Button.Button1) {
            // Handle mouse click (button release)
            int x = event.getX();
            int y = event.getY();
            int hdr = buffer.computeHeader().size();
            int ftr = computeFooter().size();
            if (y >= hdr) {
                if (y < size.getRows() - ftr) {
                    buffer.moveTo(x, y - hdr);
                } else {
                    int cols = (shortcuts.size() + 1) / 2;
                    int cw = size.getColumns() / cols;
                    int l = y - (size.getRows() - ftr) - 1;
                    int si = l * cols + x / cw;
                    String shortcut = null;
                    Iterator<String> it = shortcuts.keySet().iterator();
                    while (si-- >= 0 && it.hasNext()) {
                        shortcut = it.next();
                    }
                    if (shortcut != null) {
                        shortcut = shortcut.replaceAll("M-", "\\\\E");
                        String seq = KeyMap.translate(shortcut);
                        bindingReader.runMacro(seq);
                    }
                }
            }
        } else if (eventType == MouseEvent.Type.Wheel) {
            // Handle mouse wheel events
            if (event.getButton() == MouseEvent.Button.WheelDown) {
                buffer.moveDown(1);
            } else if (event.getButton() == MouseEvent.Button.WheelUp) {
                buffer.moveUp(1);
            }
        } else if (eventType == MouseEvent.Type.Moved) {
            // Store mouse position but don't do anything else with move events
            this.mouseX = event.getX();
            this.mouseY = event.getY();
        }
        // Explicitly ignore other event types (Dragged, Pressed, etc.)
        // This prevents two-finger scrolling from adding characters to the buffer
    }

    void enableSuspension() {
        if (!restricted && vsusp < 0) {
            Attributes attrs = terminal.getAttributes();
            attrs.setControlChar(ControlChar.VSUSP, vsusp);
            terminal.setAttributes(attrs);
        }
    }

    void toggleSuspension() {
        if (restricted) {
            setMessage("This function is disabled in restricted mode");
        } else if (vsusp < 0) {
            setMessage("This function is disabled");
        } else {
            Attributes attrs = terminal.getAttributes();
            int toggle = vsusp;
            String message = "enabled";
            if (attrs.getControlChar(ControlChar.VSUSP) > 0) {
                toggle = 0;
                message = "disabled";
            }
            attrs.setControlChar(ControlChar.VSUSP, toggle);
            terminal.setAttributes(attrs);
            setMessage("Suspension " + message);
        }
    }

    public String getTitle() {
        return title;
    }

    void resetDisplay() {
        display.clear();
        display.resize(size.getRows(), size.getColumns());
        for (Buffer buffer : buffers) {
            buffer.resetDisplay();
        }
    }

    synchronized void display() {
        display(null);
    }

    synchronized void display(final Integer editCursor) {
        if (nbBindings > 0) {
            if (--nbBindings == 0) {
                message = null;
            }
        }

        List<AttributedString> header = buffer.computeHeader();
        List<AttributedString> footer = computeFooter();

        int nbLines = size.getRows() - header.size() - footer.size();
        if (insertHelp) {
            insertHelp(suggestionBox.getSelected());
            resetSuggestion();
        }
        List<Diagnostic> diagnostics = computeDiagnostic();
        List<AttributedString> newLines = buffer.getDisplayedLines(nbLines, diagnostics);
        if (help) {
            showCompletion(newLines);
        }
        newLines.addAll(0, header);
        newLines.addAll(footer);

        // Compute cursor position
        int cursor;
        if (editMessage != null) {
            int crsr = editCursor != null ? editCursor : editBuffer.length();
            cursor = editMessage.length() + crsr;
            cursor = size.cursorPos(size.getRows() - footer.size(), cursor);
        } else {
            cursor = size.cursorPos(header.size(), buffer.getDisplayedCursor());
        }
        display.update(newLines, cursor);
        if (windowsTerminal) {
            resetDisplay();
        }
    }

    /**
     * Inserts the selected suggestion into the text.
     * <p>
     * This method is called when a suggestion is selected and should be inserted
     * into the text. Subclasses should override this method to implement the
     * insertion logic based on the selected suggestion index.
     * </p>
     * <p>
     * The selected suggestion can be retrieved from the suggestions map using:
     * {@code new ArrayList<>(suggestions.keySet()).get(selected)}
     * </p>
     *
     * @param selected the index of the selected suggestion in the suggestions list
     */
    protected void insertHelp(int selected) {}

    private void showCompletion(List<AttributedString> newLines) {
        // Only compute suggestions if something has changed
        if (suggestions == null) {
            // Get suggestions and documentation from the computeSuggestions method
            LinkedHashMap<AttributedString, List<AttributedString>> result = computeSuggestions();

            // If there are no suggestions, reset and return
            if (result == null || result.isEmpty()) {
                resetSuggestion();
                return;
            }

            suggestions = result;
        }

        // Initialize the suggestion box with the suggestions
        initBoxes(newLines);
    }

    /**
     * Initializes the suggestions map.
     * <p>
     * This method is called when suggestions need to be displayed. Subclasses should
     * override this method to return a map of suggestions to their documentation.
     * </p>
     * <p>
     * The keys in the map are AttributedString objects representing the available suggestions.
     * The values are lists of AttributedString objects containing the documentation lines
     * for each suggestion.
     * </p>
     * <p>
     * It is recommended to use a LinkedHashMap to preserve the order of suggestions,
     * as this order will be used when displaying the suggestions to the user.
     * </p>
     * <p>
     * The default implementation returns an empty map, indicating no suggestions are available.
     * </p>
     *
     * @return a map of suggestions to their documentation, or an empty map if no suggestions are available
     */
    protected LinkedHashMap<AttributedString, List<AttributedString>> computeSuggestions() {
        return new LinkedHashMap<>();
    }

    /**
     * Computes the list of diagnostics for the current buffer.
     * <p>
     * This method is called when rendering the buffer to determine if there are any
     * diagnostics (errors, warnings, etc.) that should be displayed. Subclasses should
     * override this method to provide diagnostics based on the current buffer content.
     * </p>
     * <p>
     * Diagnostics are used to highlight issues in the code and display tooltips with
     * error messages or warnings when hovering over the highlighted regions.
     * </p>
     *
     * @return a list of Diagnostic objects, or an empty list if there are no diagnostics
     */
    protected List<Diagnostic> computeDiagnostic() {
        return Collections.emptyList();
    }

    private void initBoxes(List<AttributedString> screenLines) {
        // Get the suggestions as a list
        List<AttributedString> suggestionList = new ArrayList<>(suggestions.keySet());

        // Build suggestion box, positioning it above or below based on available space
        if (suggestionBox == null) {
            suggestionBox = buildSuggestionBox(suggestionList, screenLines);
        }

        // Only proceed if we have a valid suggestion box
        if (suggestionBox != null) {
            suggestionBox.draw(screenLines);

            // Get the documentation for the selected suggestion
            int selectedIndex = suggestionBox.getSelected();
            if (selectedIndex >= 0 && selectedIndex < suggestionList.size()) {
                AttributedString selectedSuggestion = suggestionList.get(selectedIndex);
                List<AttributedString> documentation = suggestions.get(selectedSuggestion);

                if (documentation != null && !documentation.isEmpty()) {
                    Box documentationBox = buildDocumentationBox(screenLines, suggestionBox, documentation);
                    if (documentationBox != null) {
                        documentationBox.draw(screenLines);
                    }
                }
            }
        }
    }

    private Box buildSuggestionBox(List<AttributedString> suggestions, List<AttributedString> screenLines) {
        // Ensure we have suggestions to display
        if (suggestions == null || suggestions.isEmpty()) {
            return null;
        }

        // Calculate width
        // Position the box at the cursor position horizontally
        int cursorX = buffer.column;
        int xi = Math.max(printLineNumbers ? 8 : 0, cursorX);

        // Calculate the maximum width needed for suggestions
        int maxSuggestionLength =
                suggestions.stream().mapToInt(AttributedString::length).max().orElse(10) + 2;

        // Limit to a percentage of screen width
        int maxScreenWidth = (int) Math.round(size.getColumns() * 0.60);
        int xl = Math.min(xi + maxSuggestionLength, xi + maxScreenWidth);

        // Ensure the box doesn't exceed screen width
        xl = Math.min(xl, size.getColumns() - 1);

        // Calculate height
        int maxHeight = screenLines.size() - 1;

        // Limit the required height to a reasonable size (max 10 items or 1/3 of screen height)
        int maxVisibleItems = Math.min(10, maxHeight / 3);
        maxVisibleItems = Math.max(2, maxVisibleItems); // Ensure at least 2 lines for the box
        int requiredHeight = Math.min(suggestions.size(), maxVisibleItems) + 2; // +2 for borders

        // Position the box at the cursor line relative to what's displayed on screen
        int cursorLine = buffer.line;
        // Calculate the cursor position in screen coordinates (relative to firstLineToDisplay)
        int cursorScreenLine = cursorLine - buffer.firstLineToDisplay;

        // According to requirements, the box should be placed one line below the current line if possible
        int yi = cursorScreenLine + 1;
        int spaceBelow = maxHeight - yi;

        // Check if there's enough space below the cursor for the box + border
        boolean displayBelow = true;

        // If not enough space below, try to display above the cursor
        if (spaceBelow < requiredHeight) {
            // When displaying above, the bottom of the box should be one line above the cursor line
            int spaceAbove = cursorScreenLine; // Space above the cursor line on screen

            // If there's more space above than below, or not enough space below for even a minimal box
            if (spaceAbove > spaceBelow || spaceBelow < 3) { // 3 = minimum height (2 for borders + 1 for content)
                displayBelow = false;
                // Position the box so its bottom is one line above the cursor line
                yi = Math.max(0, cursorScreenLine - requiredHeight - 1);

                // If there's not enough space above for the full box, reduce its size
                if (cursorScreenLine - requiredHeight - 1 < 0) {
                    // Adjust the height to fit the available space above
                    requiredHeight = Math.max(
                            3, cursorScreenLine - 1); // Ensure at least 3 lines for the box (2 borders + 1 content)
                    yi = 0; // Start at the top of the screen
                }
            } else {
                // Not enough space below for the full box, but more than above, so reduce size to fit below
                requiredHeight = Math.max(3, spaceBelow); // Ensure at least 3 lines for the box
            }
        }

        // Build suggestion box
        int yl;
        if (displayBelow) {
            yl = Math.min(maxHeight, yi + requiredHeight);
        } else {
            yl = cursorScreenLine - 1; // End one line above the cursor line to avoid overriding it
        }

        // Ensure box dimensions are valid
        if (yl <= yi || xl <= xi) {
            return null; // Invalid box dimensions
        }

        // Ensure the box width is at least 4 characters (minimum for borders and content)
        if (xl - xi < 4) {
            xl = xi + 4;
        }

        Box box = new Box(xi, yi, xl, yl);
        box.setLines(suggestions);
        box.setSelectedStyle(
                AttributedStyle.DEFAULT.background(AttributedStyle.BLUE).foreground(AttributedStyle.WHITE));
        return box;
    }

    private Box buildDocumentationBox(
            List<AttributedString> screenLines, Box suggestionBox, List<AttributedString> documentation) {
        // Check if suggestionBox is null or screenLines is empty
        if (suggestionBox == null || screenLines == null || screenLines.isEmpty()) {
            return null;
        }

        // Check if we have documentation
        if (documentation == null || documentation.isEmpty()) {
            return null;
        }

        // calculate width
        int dXi = suggestionBox.xl;
        int dBoxSize =
                documentation.stream().mapToInt(AttributedString::length).max().orElse(10) + 2;
        int xi = Math.max(printLineNumbers ? 9 : 1, dXi);
        int maxWidth = (int) Math.round((size.getColumns() - xi) * 0.60); // let's do 60% of what's left of the screen
        int xl = Math.min(dBoxSize + xi, xi + maxWidth);

        // Ensure box width is valid
        if (xl <= xi) {
            return null; // Invalid box width
        }

        // adjust content
        documentation = adjustLines(documentation, dBoxSize - 2, xl - xi - 2);

        // calculate height
        int height = screenLines.size();
        int requiredHeight = documentation.size() + 2; // +2 for borders

        // Always align the documentation box at the same top level as the suggestion box
        int yi = suggestionBox.yi;
        int yl = yi + requiredHeight;

        // If the documentation box would extend beyond the screen, adjust it
        if (yl >= height) {
            // Try to fit it within the available space
            yl = Math.min(height - 1, yl);
            // If we can't fit all the content, reduce the height
            if (yl - yi < 3) { // Minimum height for a box (top border, content, bottom border)
                return null; // Not enough space for the documentation box
            }
        }

        // Ensure box dimensions are valid
        if (yl <= yi) {
            return null; // Invalid box height
        }

        // create documentation box
        Box documentationBox = new Box(xi, yi, xl, yl);
        documentationBox.setLines(documentation);
        documentationBox.setSelectedStyle(AttributedStyle.DEFAULT);
        return documentationBox;
    }

    private List<AttributedString> adjustLines(List<AttributedString> lines, int max, int boxLength) {
        if (max <= boxLength) {
            return lines;
        }
        List<AttributedString> adjustedLines = new ArrayList<>();
        for (AttributedString line : lines) {
            if (line.length() < boxLength) {
                adjustedLines.add(line);
            } else {
                int start = 0;
                while (start < line.length()) {
                    int stepSize = Math.min(start + boxLength, line.length());
                    int end = stepSize;
                    // check last line
                    if (end - start >= boxLength) {
                        // let's not cutoff in the middle of a word.
                        while (end > start && !Character.isWhitespace(line.charAt(end - 1))) {
                            end--;
                        }
                    }
                    if (end == start) {
                        // there was no space, let's not loop forever
                        end = stepSize;
                    }
                    adjustedLines.add(line.substring(start, end));
                    start = end;
                }
            }
        }
        return adjustedLines;
    }

    protected List<AttributedString> computeFooter() {
        List<AttributedString> footer = new ArrayList<>();

        if (editMessage != null) {
            AttributedStringBuilder sb = new AttributedStringBuilder();
            sb.style(AttributedStyle.INVERSE);
            sb.append(editMessage);
            sb.append(editBuffer);
            for (int i = editMessage.length() + editBuffer.length(); i < size.getColumns(); i++) {
                sb.append(' ');
            }
            sb.append('\n');
            footer.add(sb.toAttributedString());
        } else if (message != null || constantCursor) {
            int rwidth = size.getColumns();
            String text = "[ " + (message == null ? computeCurPos() : message) + " ]";
            int len = text.length();
            AttributedStringBuilder sb = new AttributedStringBuilder();
            for (int i = 0; i < (rwidth - len) / 2; i++) {
                sb.append(' ');
            }
            sb.style(AttributedStyle.INVERSE);
            sb.append(text);
            sb.append('\n');
            footer.add(sb.toAttributedString());
        } else {
            footer.add(new AttributedString("\n"));
        }

        Iterator<Entry<String, String>> sit = shortcuts.entrySet().iterator();
        int cols = (shortcuts.size() + 1) / 2;
        int cw = (size.getColumns() - 1) / cols;
        int rem = (size.getColumns() - 1) % cols;
        for (int l = 0; l < 2; l++) {
            AttributedStringBuilder sb = new AttributedStringBuilder();
            for (int c = 0; c < cols; c++) {
                Entry<String, String> entry = sit.hasNext() ? sit.next() : null;
                String key = entry != null ? entry.getKey() : "";
                String val = entry != null ? entry.getValue() : "";
                sb.style(AttributedStyle.INVERSE);
                sb.append(key);
                sb.style(AttributedStyle.DEFAULT);
                sb.append(" ");
                int nb = cw - key.length() - 1 + (c < rem ? 1 : 0);
                if (val.length() > nb) {
                    sb.append(val.substring(0, nb));
                } else {
                    sb.append(val);
                    if (c < cols - 1) {
                        for (int i = 0; i < nb - val.length(); i++) {
                            sb.append(" ");
                        }
                    }
                }
            }
            sb.append('\n');
            footer.add(sb.toAttributedString());
        }

        return footer;
    }

    protected void handle(Signal signal) {
        if (buffer != null) {
            size.copy(terminal.getSize());
            buffer.computeAllOffsets();
            buffer.moveToChar(buffer.offsetInLine + buffer.column);
            resetDisplay();
            display();
        }
    }

    protected void bindKeys() {
        keys = new KeyMap<>();
        if (!view) {
            keys.setUnicode(Operation.INSERT);

            for (char i = 32; i < KEYMAP_LENGTH; i++) {
                keys.bind(Operation.INSERT, Character.toString(i));
            }
            keys.bind(Operation.BACKSPACE, del());
            for (char i = 'A'; i <= 'Z'; i++) {
                keys.bind(Operation.DO_LOWER_CASE, alt(i));
            }
            keys.bind(Operation.WRITE, ctrl('O'), key(terminal, Capability.key_f3));
            keys.bind(Operation.JUSTIFY_PARAGRAPH, ctrl('J'), key(terminal, Capability.key_f4));
            keys.bind(Operation.CUT, ctrl('K'), key(terminal, Capability.key_f9));
            keys.bind(Operation.UNCUT, ctrl('U'), key(terminal, Capability.key_f10));
            keys.bind(Operation.REPLACE, ctrl('\\'), key(terminal, Capability.key_f14), alt('r'));
            keys.bind(Operation.MARK, ctrl('^'), key(terminal, Capability.key_f15), alt('a'));
            keys.bind(Operation.COPY, alt('^'), alt('6'));
            keys.bind(Operation.INDENT, alt('}'));
            keys.bind(Operation.UNINDENT, alt('{'));
            keys.bind(Operation.VERBATIM, alt('v'));
            keys.bind(Operation.INSERT, ctrl('I'), ctrl('M'));
            keys.bind(Operation.DELETE, ctrl('D'), key(terminal, Capability.key_dc));
            keys.bind(Operation.BACKSPACE, ctrl('H'));
            keys.bind(Operation.CUT_TO_END, alt('t'));
            keys.bind(Operation.JUSTIFY_FILE, alt('j'));
            keys.bind(Operation.AUTO_INDENT, alt('i'));
            keys.bind(Operation.CUT_TO_END_TOGGLE, alt('k'));
            keys.bind(Operation.TABS_TO_SPACE, alt('q'));
        } else {
            keys.bind(Operation.NEXT_PAGE, " ", "f");
            keys.bind(Operation.PREV_PAGE, "b");
        }
        keys.bind(Operation.NEXT_PAGE, ctrl('V'), key(terminal, Capability.key_f8));
        keys.bind(Operation.PREV_PAGE, ctrl('Y'), key(terminal, Capability.key_f7));

        keys.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));
        keys.bind(Operation.QUIT, ctrl('X'), key(terminal, Capability.key_f2));

        keys.bind(Operation.READ, ctrl('R'), key(terminal, Capability.key_f5));
        keys.bind(Operation.SEARCH, ctrl('W'), key(terminal, Capability.key_f6));

        keys.bind(Operation.CUR_POS, ctrl('C'), key(terminal, Capability.key_f11));
        keys.bind(Operation.TO_SPELL, ctrl('T'), key(terminal, Capability.key_f11));

        keys.bind(Operation.GOTO, ctrl('_'), key(terminal, Capability.key_f13), alt('g'));
        keys.bind(Operation.NEXT_SEARCH, key(terminal, Capability.key_f16), alt('w'));

        keys.bind(Operation.RIGHT, ctrl('F'));
        keys.bind(Operation.LEFT, ctrl('B'));
        keys.bind(Operation.NEXT_WORD, translate("^[[1;5C")); // ctrl-left
        keys.bind(Operation.PREV_WORD, translate("^[[1;5D")); // ctrl-right
        keys.bind(Operation.NEXT_WORD, alt(key(terminal, Capability.key_right)));
        keys.bind(Operation.PREV_WORD, alt(key(terminal, Capability.key_left)));
        keys.bind(Operation.NEXT_WORD, alt(translate("^[[C")));
        keys.bind(Operation.PREV_WORD, alt(translate("^[[D")));
        keys.bind(Operation.LSP_SUGGESTION, ctrl(' '));
        keys.bind(Operation.UP, ctrl('P'));
        keys.bind(Operation.DOWN, ctrl('N'));

        keys.bind(Operation.BEGINNING_OF_LINE, ctrl('A'), key(terminal, Capability.key_home));
        keys.bind(Operation.END_OF_LINE, ctrl('E'), key(terminal, Capability.key_end));
        keys.bind(Operation.BEGINNING_OF_PARAGRAPH, alt('('), alt('9'));
        keys.bind(Operation.END_OF_PARAGRAPH, alt(')'), alt('0'));
        keys.bind(Operation.FIRST_LINE, alt('\\'), alt('|'));
        keys.bind(Operation.LAST_LINE, alt('/'), alt('?'));

        keys.bind(Operation.MATCHING, alt(']'));
        keys.bind(Operation.SCROLL_UP, alt('-'), alt('_'));
        keys.bind(Operation.SCROLL_DOWN, alt('+'), alt('='));

        keys.bind(Operation.PREV_BUFFER, alt('<'));
        keys.bind(Operation.NEXT_BUFFER, alt('>'));
        keys.bind(Operation.PREV_BUFFER, alt(','));
        keys.bind(Operation.NEXT_BUFFER, alt('.'));

        keys.bind(Operation.COUNT, alt('d'));
        keys.bind(Operation.CLEAR_SCREEN, ctrl('L'));

        keys.bind(Operation.HELP, alt('x'));
        keys.bind(Operation.CONSTANT_CURSOR, alt('c'));
        keys.bind(Operation.ONE_MORE_LINE, alt('o'));
        keys.bind(Operation.SMOOTH_SCROLLING, alt('s'));
        keys.bind(Operation.MOUSE_SUPPORT, alt('m'));
        keys.bind(Operation.WHITESPACE, alt('p'));
        keys.bind(Operation.HIGHLIGHT, alt('y'));

        keys.bind(Operation.SMART_HOME_KEY, alt('h'));
        keys.bind(Operation.WRAP, alt('l'));

        keys.bind(Operation.BACKUP, alt('b'));
        keys.bind(Operation.NUMBERS, alt('n'));

        keys.bind(Operation.UP, key(terminal, Capability.key_up));
        keys.bind(Operation.DOWN, key(terminal, Capability.key_down));
        keys.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        keys.bind(Operation.LEFT, key(terminal, Capability.key_left));

        // Bind all possible mouse event prefixes
        // This ensures mouse events are recognized regardless of the terminal's kmous capability
        keys.bind(Operation.MOUSE_EVENT, MouseSupport.keys(terminal));

        keys.bind(Operation.TOGGLE_SUSPENSION, alt('z'));
        keys.bind(Operation.NEXT_PAGE, key(terminal, Capability.key_npage));
        keys.bind(Operation.PREV_PAGE, key(terminal, Capability.key_ppage));
    }

    protected enum Operation {
        DO_LOWER_CASE,

        QUIT,
        WRITE,
        READ,
        GOTO,
        FIND,

        WRAP,
        NUMBERS,
        SMOOTH_SCROLLING,
        MOUSE_SUPPORT,
        ONE_MORE_LINE,
        CLEAR_SCREEN,

        UP,
        DOWN,
        LEFT,
        RIGHT,

        INSERT,
        BACKSPACE,

        NEXT_BUFFER,
        PREV_BUFFER,

        HELP,
        NEXT_PAGE,
        PREV_PAGE,
        SCROLL_UP,
        SCROLL_DOWN,
        NEXT_WORD,
        PREV_WORD,
        LSP_SUGGESTION,
        BEGINNING_OF_LINE,
        END_OF_LINE,
        FIRST_LINE,
        LAST_LINE,

        CUR_POS,

        CASE_SENSITIVE,
        BACKWARDS,
        REGEXP,
        ACCEPT,
        CANCEL,
        SEARCH,
        TOGGLE_REPLACE,
        MAC_FORMAT,
        DOS_FORMAT,
        APPEND_MODE,
        PREPEND_MODE,
        BACKUP,
        TO_FILES,
        YES,
        NO,
        ALL,
        NEW_BUFFER,
        EXECUTE,
        NEXT_SEARCH,
        MATCHING,
        VERBATIM,
        DELETE,

        JUSTIFY_PARAGRAPH,
        TO_SPELL,
        CUT,
        REPLACE,
        MARK,
        COPY,
        INDENT,
        UNINDENT,
        BEGINNING_OF_PARAGRAPH,
        END_OF_PARAGRAPH,
        CUT_TO_END,
        JUSTIFY_FILE,
        COUNT,
        CONSTANT_CURSOR,
        WHITESPACE,
        HIGHLIGHT,
        SMART_HOME_KEY,
        AUTO_INDENT,
        CUT_TO_END_TOGGLE,
        TABS_TO_SPACE,
        UNCUT,

        MOUSE_EVENT,

        TOGGLE_SUSPENSION
    }

    /**
     * A class representing a box to be drawn on the screen.
     * <p>
     * The box is defined by its coordinates in the terminal:
     * </p>
     * <pre>
     * y axis (xi,yi)┌──────────────────────────────┐(xl,yi)
     *               │                              │
     *               │                              │
     *               │                              │
     *               │                              │
     *               │                              │
     *               │                              │
     *               │                              │
     *        (xi,yl)└──────────────────────────────┘(xl,yl)
     *                           x axis
     * </pre>
     * <p>
     * The box can contain a list of lines to display, with support for scrolling
     * if there are more lines than can fit in the box. It also supports highlighting
     * a selected line with a different style.
     * </p>
     */
    class Box {
        // (xi,yi) upper left
        // (xl,yi) upper right
        // (xi,yl) lower left
        // (xl,yl) lower right
        private final int xi, xl, yi, yl;
        private List<AttributedString> lines;
        private int selected = 0;
        private int selectedInView = 0;
        private final int height;
        private AttributedStyle selectedStyle = AttributedStyle.DEFAULT;
        private List<AttributedString> visibleLines;

        /**
         * Creates a new box with the specified coordinates.
         *
         * @param xi the x-coordinate of the upper-left corner
         * @param yi the y-coordinate of the upper-left corner
         * @param xl the x-coordinate of the lower-right corner
         * @param yl the y-coordinate of the lower-right corner
         */
        private Box(int xi, int yi, int xl, int yl) {
            this.xi = xi;
            this.yi = yi;
            this.xl = xl;
            this.yl = yl;
            // Ensure height is at least 1 to avoid issues with subList
            this.height = Math.max(1, yl - yi - 1);
        }

        /**
         * Sets the content lines to be displayed in the box.
         * <p>
         * This method also initializes the visible lines based on the box height.
         * If the number of lines exceeds the box height, only the first {@code height}
         * lines will be visible initially.
         * </p>
         *
         * @param lines the lines to display in the box
         */
        private void setLines(List<AttributedString> lines) {
            if (lines == null) {
                this.lines = Collections.emptyList();
                this.visibleLines = Collections.emptyList();
                return;
            }

            this.lines = lines;
            if (height > 0 && !lines.isEmpty()) {
                this.visibleLines = lines.subList(0, Math.min(height, lines.size()));
            } else {
                this.visibleLines = Collections.emptyList();
            }
        }

        /**
         * Gets the index of the currently selected line.
         *
         * @return the index of the selected line in the full list of lines
         */
        public int getSelected() {
            return selected;
        }

        /**
         * Sets the style to use for the selected line.
         *
         * @param selectedStyle the style to apply to the selected line
         */
        private void setSelectedStyle(AttributedStyle selectedStyle) {
            this.selectedStyle = selectedStyle;
        }

        private AttributedStyle getSelectedStyle() {
            return selectedStyle;
        }

        private void down() {
            selected = Math.floorMod(selected + 1, lines.size());
            if (!scrollable() || selectedInView < height - 1) {
                selectedInView++;
                return;
            }
            // calculate the new view
            if (selected == 0) {
                // return to the beginning of the list
                selectedInView = 0;
                visibleLines = lines.subList(0, height);
            } else {
                visibleLines = lines.subList(selected - height + 1, selected + 1);
            }
        }

        private void up() {
            selected = Math.floorMod(selected - 1, lines.size());
            if (!scrollable() || selectedInView > 0) {
                selectedInView--;
                return;
            }
            // calculate the new view
            if (selected == lines.size() - 1) {
                // last element in the list, return to beginning
                this.selectedInView = this.height - 1;
                this.visibleLines = this.lines.subList(this.lines.size() - height, this.lines.size());
            } else {
                this.visibleLines = this.lines.subList(selected, selected + height);
            }
        }

        private boolean scrollable() {
            return this.height < this.lines.size();
        }

        private int getSelectedInView() {
            return Math.floorMod(selectedInView, lines.size());
        }

        /**
         * Draws the box on the screen.
         * <p>
         * This method draws the box borders and content on the provided screen lines.
         * It modifies the screen lines in place to include the box.
         * </p>
         *
         * @param screenLines the screen lines to draw the box on
         */
        public void draw(List<AttributedString> screenLines) {
            addBoxBorders(screenLines);
            addBoxLines(screenLines);
        }

        protected void addBoxBorders(List<AttributedString> newLines) {
            // Check if box is null or newLines is empty
            if (newLines == null || newLines.isEmpty()) {
                return;
            }

            // Ensure the box is within the screen bounds
            if (yi >= newLines.size() || yl >= newLines.size()) {
                return; // Box is completely outside the screen
            }

            int width = xl - xi;
            if (width <= 0) {
                return; // Invalid box width
            }

            // Ensure minimum width for borders
            if (width < 3) {
                width = 3; // Minimum width for borders (left, content, right)
            }

            // Draw the top border if it's within the screen bounds
            if (yi >= 0) {
                AttributedStringBuilder top = new AttributedStringBuilder(width);
                top.append('┌');
                top.append('─', width - 2);
                top.append('┐');
                setLineInBox(newLines, yi, top.toAttributedString(), true);
            }

            // Draw side borders
            AttributedStringBuilder sides = new AttributedStringBuilder(width);
            sides.append('│');
            sides.append(' ', width - 2);
            sides.append('│');
            AttributedString side = sides.toAttributedString();

            // Only draw sides within the screen bounds
            int startY = Math.max(yi + 1, 0);
            int endY = Math.min(yl, newLines.size() - 1);

            for (int y = startY; y < endY; y++) {
                setLineInBox(newLines, y, side, true);
            }

            // Draw the bottom border if it's within the screen bounds
            if (yl >= 0 && yl < newLines.size()) {
                AttributedStringBuilder bottom = new AttributedStringBuilder(width);
                bottom.append('└');
                bottom.append('─', width - 2);
                bottom.append('┘');
                setLineInBox(newLines, yl, bottom.toAttributedString(), true);
            }
        }

        protected void setLineInBox(List<AttributedString> newLines, int y, AttributedString line, boolean borders) {
            // Check if the line index is valid
            if (y < 0 || y >= newLines.size()) {
                return; // Skip if the line index is out of bounds
            }

            // Calculate start and end positions for the box content
            int start = xi;
            int end = xl;

            // Adjust for non-border content (inside the box)
            if (!borders) {
                start++;
                end--;
            }

            // Ensure start and end are within valid bounds
            start = Math.max(0, start);
            end = Math.min(end, size.getColumns() - 1);

            // Get the current line content
            AttributedString currLine = newLines.get(y);
            AttributedStringBuilder newLine = new AttributedStringBuilder(Math.max(end + 1, currLine.length() + 1));
            int currLength = currLine.length();

            // Handle newline at the end of the line
            boolean hasNewline = false;
            if (currLength > 0 && currLine.charAt(currLength - 1) == '\n') {
                currLength -= 1;
                hasNewline = true;
            }

            // Copy the content before the box
            newLine.append(currLine, 0, Math.min(start, currLength));

            // Add padding if needed
            if (start > currLength) {
                newLine.append(' ', start - currLength);
            }

            // Add the box content, ensuring it doesn't exceed the screen width
            int contentWidth = Math.min(line.length(), end - start);
            if (contentWidth > 0) {
                newLine.append(line, 0, contentWidth);
            }

            // Add the content after the box
            int afterBoxStart = start + contentWidth;
            if (afterBoxStart < currLength) {
                newLine.append(currLine, afterBoxStart, currLength);
            }

            // Add newline if it was in the original line
            if (hasNewline) {
                newLine.append('\n');
            }

            newLines.set(y, newLine.toAttributedString());
        }

        protected void addBoxLines(List<AttributedString> screenLines) {
            // Check if box is null or screenLines is empty
            if (screenLines == null || screenLines.isEmpty() || visibleLines == null || visibleLines.isEmpty()) {
                return;
            }

            // Calculate the maximum number of lines that can fit in the box
            int maxLines = yl - yi - 1;
            if (maxLines <= 0) {
                return; // No space for content
            }

            // Ensure we don't try to display more lines than can fit in the box
            int linesToDisplay = Math.min(visibleLines.size(), maxLines);

            for (int i = 0; i < linesToDisplay; i++) {
                AttributedStringBuilder line = new AttributedStringBuilder(xl - xi - 2);
                AttributedStyle background = AttributedStyle.DEFAULT;
                if (i == getSelectedInView()) {
                    background = getSelectedStyle();
                }
                line.append(visibleLines.get(i), background);
                line.style(background);
                line.append(' ', xl - xi - line.length() - 2);

                // Ensure we don't try to write outside the screen bounds
                int lineY = yi + 1 + i;
                if (lineY < screenLines.size()) {
                    setLineInBox(screenLines, lineY, line.toAttributedString(), false);
                }
            }
        }
    }
}
