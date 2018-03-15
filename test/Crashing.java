package test;

public class Crashing
{
    public static void main(String[] args) throws java.io.IOException
    {
        byte[] buffer = new byte[100];
        // Make this crash on very likely generated inputs but not on
        // files inside in/ directory:
        if (System.in.read(buffer) > 10) {
            throw new RuntimeException("I will crash now!");
        }
    }
}
