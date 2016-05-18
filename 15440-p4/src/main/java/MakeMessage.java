/**
 * This is a class for serialize and deserialize message at both sides.
 * Created by yuqil on 4/7/16.
 */
import java.io.*;
public class MakeMessage implements Serializable {
    /**
     * Serialize message template to byte array
     * @param obj message
     * @return byte array representation of the object
     */
    public static byte[] serialize(Object obj) {
        try {
            ByteArrayOutputStream byteArrayOut = new ByteArrayOutputStream();
            ObjectOutputStream objectOut = new ObjectOutputStream(byteArrayOut);
            objectOut.writeObject(obj);
            return byteArrayOut.toByteArray();
        } catch (final IOException e) {
            e.printStackTrace(System.err);
            return new byte[0];
        }
    }

    /**
     * Deserialize a byte array to a type T
     * @param objectBytes: bytes array
     * @param type: object type
     * @param <T>: object class
     * @return object of the byte array, null if failed to read
     */
    public static <T> T deserialize(byte[] objectBytes, Class<T> type) {
        try {
            ByteArrayInputStream byteArrayIn = new ByteArrayInputStream(objectBytes);
            ObjectInputStream objectIn = new ObjectInputStream(byteArrayIn);
            return (T) objectIn.readObject();
        } catch (final Exception e) {
            e.printStackTrace(System.err);
            return null;
        }
    }
}
