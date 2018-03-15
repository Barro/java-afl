package test;

public class NoAttribute
{
    public static void main(String[] args) throws java.io.IOException
    {
        String value = "value";
        byte[] buffer = new byte[5];
        javafl.JavaAfl.init();
        System.in.read(buffer);
        String read = new String(buffer);
        if (read.equals(value)) {
            System.out.println("Got value!");
        } else {
            System.out.println("Got something else: " + read);
        }
    }
}
