import java.io.Serializable;
import java.util.ArrayList;

/**
 * This is a template for message between server and user_node.
 * Created by yuqil on 4/7/16.
 */
public class MessageTemplate implements Serializable {
    String filename;  // filename for commit
    int opcode = -1;  // use to record operation result
    String[] sources; // source files
    byte[] img;       // img content


    /**
     * Constructor for message.
     * @param filename
     * @param sources
     * @param opcode
     * @param img
     */
    public MessageTemplate(String filename, String[] sources, int opcode, byte[] img) {
        this.opcode = opcode;
        this.filename = filename;
        this.sources = sources;
        this.img = img;
    }

    /**
     * Constructor for message from an arraylist of source files.
     * @param filename
     * @param sources
     * @param opcode
     * @param img
     */
    public MessageTemplate(String filename, ArrayList<String> sources, int opcode, byte[] img) {
        String[] strs = new String[sources.size()];
        for (int i = 0; i < sources.size(); i ++) {
            strs[i] = sources.get(i);
        }

        this.opcode = opcode;
        this.filename = filename;
        this.sources = strs;
        this.img = img;
    }
}
