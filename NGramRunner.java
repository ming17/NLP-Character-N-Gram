import java.util.Random;
import java.util.Scanner;
import java.io.*;
import java.lang.*;

public class NGramRunner
{
    private static final double HELD_OUT_FRAC = 0.05;
    private static final int NEW_LINE_CHAR = 10;
    private static final int SPACE_CHAR = 32;
    private static final boolean DEBUG = false;

    private enum Mode 
    {
        NORMAL, ADD_1, ADD_K, LI_EL, LI_TL
    }
    
    public static NGram readFile(RandomAccessFile raf, boolean dev) throws IOException
    {
        try {
            Random rand = new Random();
            long size = raf.length();
            int heldOutSize = (int)Math.floor(size*HELD_OUT_FRAC);
            int heldOutStart = (int)Math.floor(size * rand.nextDouble() * (1-HELD_OUT_FRAC));
            raf.seek(0);

            if(DEBUG) 
                System.out.println("Held out size is " + heldOutSize + " bytes, held out start is " + heldOutStart + " and held out end is " + (heldOutStart + heldOutSize) + " of " + size + " total bytes");
            NGram NG = new NGram(heldOutSize);
            int c1, c2, c3;
            int bytesRead = 0;

            c1 = raf.read();
            c2 = raf.read();
            c3 = raf.read();

            if(c1 == NEW_LINE_CHAR)
                c1 = SPACE_CHAR;
            else if(c2 == NEW_LINE_CHAR)
                c2 = SPACE_CHAR;

            if(DEBUG)
                System.out.println("c1 3 characters are " + (char)c1 + " (" + c1 + "), " + (char)c2 + " (" + c2 + "), " + (char)c3 + " (" + c3 + ")\n");

            // Build NGram
            do
            {
                if(c3 == NEW_LINE_CHAR)
                    c3 = SPACE_CHAR;

                // if(DEBUG)
                //    System.out.println("c1 = " + (char)c1 + " (" + c1 + "), c2 = " + (char)c2 + " (" + c2 + "), c3 = " + (char)c3 + " (" + c3 + ")");

                if(dev && bytesRead >= heldOutStart && bytesRead < heldOutStart + heldOutSize)
                    NG.addToDevset(c1);
                else
                    NG.addSequence(c1, c2, c3);
                bytesRead++;
                c1 = c2;
                c2 = c3;

            } while((c3=raf.read()) != -1);
            
            if(DEBUG)
                System.out.println("Last 3 characters are " + (char)c1 + " (" + c1 + "), " + (char)c2 + " (" + c2 + "), " + (char)c3 + " (" + c3 + ")\n");

            // Add last few characters
            if(dev && bytesRead >= heldOutStart && bytesRead < heldOutStart + heldOutSize)
            {
                if(c1 != -1)
                    NG.addToDevset(c1);
                if(c2 != -1)
                    NG.addToDevset(c2);
            }
            else
            {
                if(c1 != -1)
                    NG.addSequence(c1, c2, -1);
                if(c2 != -1)
                    NG.addSequence(c2, -1, -1);
            }
            return NG;
        } catch (IOException e) {
            System.err.println(e);
        }

        return null;
    }

    public static void writeModel(String filename, NGram ng) throws IOException
    {
        try {
            switch(ng.getMode())
            {
                case NORMAL:
                    filename = "outputs/" + filename + "_norm.txt";
                    break;
                case ADD_1:
                    filename = "outputs/" + filename + "_add1.txt";
                    break;
                case ADD_K:
                    filename = "outputs/" + filename + "_addk.txt";
                    break;
                case LI_EL: 
                    filename = "outputs/" + filename + "_linint_eqLam.txt";
                    break;
                case LI_TL:
                    filename = "outputs/" + filename + "_linint_tunLam.txt";
                    break;
                default:
                    return;
            }

            if(DEBUG) 
                System.out.println("Filename is " + filename);
            File f = new File(filename);
            f.createNewFile();

            FileWriter fout = new FileWriter(filename);
            ng.printToFile(fout);
            //fout.write(ng.toString());
            fout.close();
        } catch(IOException e) {
            System.err.println(e);
        }
    }

    // read test file, 
    public static double[] readTestFile(NGram ng, String testFile, String filename) throws IOException
    {
        try {
            RandomAccessFile tf = new RandomAccessFile(testFile, "r");
            double[] perplexities = new double[3];
            double[] tempP;
            int[] sentence = new int[1000];
            int numSentences = 0;
            int numChars;
            int currChar;

            do {
                numChars = 0;
                currChar = tf.read();

                while(currChar != NEW_LINE_CHAR && currChar != -1)
                {
                    sentence[numChars] = currChar;
                    numChars++;
                    currChar = tf.read();
                }

                if(numChars > 0)
                {
                    numSentences++;
                    tempP = ng.getPerplexity(sentence, numChars);
                    for(int i = 0; i < tempP.length; i++)
                        perplexities[i] += tempP[i];
                    //Print perplexities for every sentence
                    if(filename == "training.en" && DEBUG)
                    {
                        System.out.print("Sentence: ");
                        for(int i = 0; i < numChars; i++)
                        {
                            System.out.print((char)sentence[i]);
                        }
                        System.out.print("\n\tUnigram perplexity is " + perplexities[0] + "\n\tBigram perplexity is " + perplexities[1] + "\n\tTrigram perplexity is " + perplexities[2] + "\n\n");
                    }
                }
                
            } while(currChar != -1);

            tf.close();

            for(int i = 0; i < perplexities.length; i++)
            {
                perplexities[i] /= numSentences;
            }
            if(DEBUG)
            {
                System.out.println("File: " + filename);
                System.out.print("\n\tAverage unigram perplexity is " + perplexities[0] + "\n\tAverage bigram perplexity is " + perplexities[1] + "\n\tAverage trigram perplexity is " + perplexities[2] + "\n");
            }

            return perplexities;
        } catch(IOException e){
            System.err.println(e);
        }

        return null;
    }

    public static String generateText(NGram ng, int c1, int c2)
    {
        String str = "";
        int numChars = 0;
        int nextChar = 0;

        // Bigram generation
        if(c2 == -1)
        {
            str += "Bigram generation: " + (char)c1;
            while(numChars < 100 && nextChar != -1)
            {
                nextChar = ng.getNextLetter(c1);
                str += (char)nextChar;
                c1 = nextChar;
                numChars++;
            }
        }
        // Trigram generation
        else
        {
            str += "Trigram generation: " + (char)c1 + "" + (char)c2;
            while(numChars < 100 && nextChar != -1)
            {
                nextChar = ng.getNextLetter(c1, c2);
                str += (char)nextChar;
                c1 = c2; 
                c2 = nextChar;
                numChars++;
            }
        } 
        str += ("\n");

        return str;
    }

    public static void main (String [] args) throws IOException
    {
        String[] files = {"training.en"};//, "training.de", "training.es"};
        RandomAccessFile[] rafs = new RandomAccessFile[files.length];
        NGram[] ngrams = new NGram[files.length];
        Mode[] ms = {Mode.NORMAL, Mode.ADD_1, Mode.LI_EL, Mode.ADD_K, Mode.LI_TL};
        Scanner kbInput = new Scanner(System.in);
        String temp = "";
        int mode = 0;

        int textGenMode = -1;
        int c1 = -1, c2 = -1;
        if(args.length > 0)
            c1 = (int)args[0].charAt(0);
        if(args.length > 1)
            c2 = (int)args[1].charAt(0);

        boolean useDevset = false;
        boolean lastDevset = false;
        boolean firstTime = true;

        double[] perplexities;
        double[][] bestPerplexities = new double[Mode.values().length][3];
        int[][] Pidx = new int[Mode.values().length][3];

        for(int i = 0; i < Mode.values().length; i++)
            for(int j = 0; j < 3; j++)
                bestPerplexities[i][j] = -1;

        do {
            System.out.print("\nPlease input desired mode (0-4)\n0-NORMAL, 1-ADD_1, 2-ADD-K, 3-LI_EL, 4-LI_TL (-1 to quit): ");
            mode = kbInput.nextInt();
        } while(mode < -1 || mode > 4);

        while(mode != -1)
        {
            // read in files
            for(int f = 0; f < files.length; f++)
            {
                try {
                    rafs[f] = new RandomAccessFile("assignment1-data/" + files[f], "r");

                    if(mode == Mode.ADD_K.ordinal() || mode == Mode.LI_TL.ordinal())
                        useDevset = true;
                    else
                        useDevset = false;
                    
                    if(DEBUG)
                        System.out.println("Last devset was " + lastDevset + " and current devset is " + useDevset);
                    if(lastDevset != useDevset || firstTime)
                        ngrams[f] = readFile(rafs[f], useDevset);

                    ngrams[f].configure(mode);
                    writeModel(files[f], ngrams[f]);

                    // Reads test file and generates perplexity
                    perplexities = readTestFile(ngrams[f], "assignment1-data/test", files[f]);
                    for(int i = 0; i < 3; i++)
                    {
                        if(perplexities[i] < bestPerplexities[mode][i] || bestPerplexities[mode][i] == -1)
                        {
                            bestPerplexities[mode][i] = perplexities[i];
                            Pidx[mode][i] = f;
                        }
                        if(DEBUG)
                            System.out.println("Perplexity for " + (i+1) + "-gram is " + perplexities[i]);
                    }

                    if(files[f].equals("training.en"))
                    {
                        // if characters inputted on command line
                        if(c1 != -1)
                        {
                            System.out.println("\t" + generateText(ngrams[f], c1, c2));
                        }
                        // generate text based on user input
                        else
                        {
                            do {
                                do {
                                    System.out.print("\tFile: " + files[f] + ", Please enter N for N-gram text generation, 2-bigram, 3-trigram (-1 to quit): ");
                                    textGenMode = kbInput.nextInt();
                                } while(textGenMode != 2 && textGenMode != 3 && textGenMode != -1);

                                if(textGenMode != -1)
                                {
                                    do {
                                        c1 = -1;
                                        c2 = -1;

                                        System.out.print("\tEnter first character (-1 to quit): ");
                                        temp = kbInput.next();
                                        if(temp.charAt(0) == '-' && temp.charAt(1) == '1') 
                                            c1 = -1;
                                        else
                                            c1 = (int)temp.charAt(0);
                                        if(textGenMode == 3 && c1 != -1)
                                        {
                                            System.out.print("\tEnter second character (-1 to quit): ");
                                            temp = kbInput.next();
                                            if(temp.charAt(0) == '-' && temp.charAt(1) == '1') 
                                                c2 = -1;
                                            else
                                                c2 = (int)temp.charAt(0);
                                        }

                                        if((textGenMode == 2 && c1 != -1) || (textGenMode == 3 && c1 != -1 && c2 != -1))
                                            System.out.println("\t\t" + generateText(ngrams[f], c1, c2));
                                    } while((textGenMode == 2 && c1 != -1) || (textGenMode == 3 && c1 != -1 && c2 != -1));
                                }
                            } while(textGenMode != -1);
                        }
                    }

                    rafs[f].close();

                } catch(IOException e) {
                    System.err.println(e);
                }
            }
            firstTime = false;
            lastDevset = useDevset;

            do {
                System.out.print("\nPlease input desired mode (0-4)\n0-NORMAL, 1-ADD_1, 2-ADD_K, 3-LI_EL, 4-LI_TL (-1 to quit): ");
                mode = kbInput.nextInt();
            } while(mode < -1 || mode > 4);
        }

        for(int i = 0; i < Mode.values().length; i++)
        {
            if(bestPerplexities[i][0] != -1)
                System.out.println("Best average unigram perplexity for mode " + Mode.values()[i].name() + " is " + bestPerplexities[i][0] + " at file " + files[Pidx[i][0]]);
            if(bestPerplexities[i][1] != -1)
                System.out.println("Best average bigram perplexity for mode " + Mode.values()[i].name() + " is " + bestPerplexities[i][1] + " at file " + files[Pidx[i][1]]);
            if(bestPerplexities[i][2] != -1) 
                System.out.println("Best average trigram perplexity for mode " + Mode.values()[i].name() + " is " + bestPerplexities[i][2] + " at file " + files[Pidx[i][2]] + "\n");
        }
    }
}