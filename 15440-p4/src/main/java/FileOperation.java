/**
 * This is a class for file operation regarding logs.
 * Created by yuqil on 4/7/16.
 */

import java.io.IOException;
import java.nio.file.Paths;
import java.io.*;
import java.nio.file.*;

public class FileOperation {
    public String dir_path;           // dirctory path
    public BufferedWriter log_writer; // log writer for sequential writing

    /* Global Macro */
    public static final byte ASK_USER_MSG = 0;
    public static final byte REPLY_USER_COMMIT = 1;
    public static final byte REPLY_USER_FAILURE = 2;

    public static final byte USER_AGREE = 3;
    public static final byte USER_DISAGREE = 4;
    public static final byte USER_ACK = 5;

    public static final int COMMIT_SUCCESS = 6;
    public static final int COMMIT_FAILURE = 7;
    public static final int COMMIT_INCOMPLETE = 8;
    public static final int TIMEOUT = 3000;

    /**
     * Constructor Function.
     * @param relative_path of the file
     * @throws IOException
     */
    public FileOperation(String relative_path) throws IOException {
        dir_path = Paths.get(".").toAbsolutePath().normalize().toString() + "/";
        log_writer= new BufferedWriter(new FileWriter(dir_path+"LOG.txt", true));
    }


    /**
     * Get the log file reader
     * @return a buffered reader for log file
     * @throws FileNotFoundException
     */
    public BufferedReader getLogReader () throws FileNotFoundException {
        return new BufferedReader(new FileReader("LOG.txt"));
    }


    /**
     * Write a new file in the directory.
     * @param filename: File name
     * @param img: image content
     * @return true for success and false for failure.
     */
    public boolean addFile(String filename, byte[] img){
        try {
            FileOutputStream out = new FileOutputStream(dir_path + filename);
            out.write(img);
            out.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return false;
        }
    }


    /**
     * Remote a file in the directory if exists.
     * @param filename
     * @return true for success and false for failure.
     */
    public boolean removeFile(String filename) {
        String path_string = dir_path + filename;
        Path path = Paths.get(path_string);
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return false;
        }
    }


    /**
     * Check if a file exist in the directory
     * @param filename filename
     * @return true if exists, false if not.
     */
    public synchronized boolean hasFile (String filename) {
        File f = new File(filename);
        if(f.exists()){
            return true;
        }
        else{
            return false;
        }
    }


    /**
     * Append a log to LOG.txt for server side.
     * @param log Log object
     * @return true for success and false for failure.
     */
    public synchronized boolean writeLog (Log log) {
        try {
            log_writer.write(log.toString());
            log_writer.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return false;
        }
    }

    /**
     * Append a log for a single file at client side.
     * @param source local source file needed
     * @param collage collage needs to be commited
     * @return true for success and false for failure.
     */
    public synchronized boolean writeLog (String source, String collage) {
        try {
            log_writer.write(source + " log " + collage + "\n");
            log_writer.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return false;
        }
    }


    /**
     * Append a log for source files at client side.
     * @param sources local source file needed
     * @param collage collage needs to be commited
     */
    public synchronized void writeLog (String[] sources, String collage) {
        for (String source : sources) {
            writeLog(source, collage);
        }
    }

    /**
     * Write commit result for a single file to Log.txt
     * @param id for commit filename
     * @param commit commit decision
     * @return true for success and false for failure.
     */
    public synchronized boolean writeResult (String id, int commit) {
        try {
            log_writer.write(id + " result " + String.valueOf(commit) + "\n");
            log_writer.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return false;
        }
    }


    /**
     * Write commit result for all source files to Log.txt at client side
     * @param ids all sources files
     * @param commit commit decision
     */
    public synchronized void writeResult (String[] ids, int commit) {
        for (String id : ids) {
            writeResult(id, commit);
        }
    }



    /**
     * Write a finished log to log.txt
     * @param id filename for commit
     * @return true for success and false for failure.
     */
    public synchronized boolean writeFinishedLog (String id) {
        try {
            log_writer.write(id + " finished\n");
            log_writer.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return false;
        }
    }


    /**
     * Log class use for logging commit operations.
     */
    public static class Log {
        public String filename;
        public byte[] img;
        public String[] sources;
        private int commit = COMMIT_INCOMPLETE;  // commit decision

        public Log(String filename, byte[] img, String[] sources) {
            this.img = img;
            this.sources = sources;
            this.filename = filename;
        }

        /**
         * A readable representation for logs.
         * @return A readable representation for logs.
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(filename + " log");
            for (int i = 0; i < sources.length; i ++) {
                sb.append(" " + sources[i]);
            }
            sb.append("\n");
            return sb.toString();
        }
    }
}
