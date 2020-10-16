import java.lang.*;
import java.util.Random;
import java.io.*;

public class NGram 
{
    // SIZE accounts for all possible characters including digits, upper and lower case characters, spaces, and symbols, ASCII values 32-126
    private static final int SIZE = 224;
    private static final int OFFSET = 32;
    private static final int TUNING_ITERATIONS = 6;

    // model could be one of 5 types: normal (0), add-1 smoothing (1), tuned add-k smoothing (2), 
    //      equal lambda linear interpolation (3), or tuned lambda linear interpolation (4)
    public enum Mode 
    {
        NORMAL, ADD_1, ADD_K, LI_EL, LI_TL
    }

    private int dictionarySize;
    private int numChars;
    private NGramNode[] nodes;
    private Mode mode;
    private int[] devset;
    private int devIdx;
    private double[] ks;
    private double[] lambdas;

    public NGram(int devSize)
    {
        dictionarySize = 0;
        numChars = 0;
        nodes = new NGramNode[SIZE];
        mode = Mode.NORMAL;

        devset = new int[devSize];
        devIdx = 0;
        ks = new double[2];
        lambdas = new double[5];;
    }

    private static int toUpper(int c)
    {
        if (c >= 97 && c <= 122)
            return (c-32);
        return c;
    }

    public boolean addSequence(int c1, int c2, int c3)
    {
        c1 = toUpper(c1);
        c2 = toUpper(c2);
        c3 = toUpper(c3);
        numChars++;
        if(nodes[c1-OFFSET] == null)
            addNode(c1);
        return nodes[c1-OFFSET].addSeq(c2, c3);
    }

    private boolean addNode(int character)
    {
        character = toUpper(character);
        nodes[character-OFFSET] = new NGramNode(character, 0);
        dictionarySize++;
        return true;
    }

    private int dictionarySize()
    {
        return dictionarySize;
    }

    public Mode getMode()
    {
        return mode;
    }

    private double unigramProb(int character)
    {
        character = toUpper(character);
        if(nodes[character-OFFSET] == null)
            return 0;

        switch(mode)
        {
            case NORMAL:
            case LI_EL:
            case LI_TL:
                return (double)nodes[character-OFFSET].getCount()/totalChars();
            case ADD_1:
            case ADD_K:
                return (double)(nodes[character-OFFSET].getCount()+1)/(totalChars()+dictionarySize);
            default: 
                return -1;
        }
    }

    private double bigramProb(int c1, int c2)
    {
        c1 = toUpper(c1);
        c2 = toUpper(c2);
        int idx1 = c1-OFFSET;
        if(nodes[idx1] == null || nodes[c2-OFFSET] == null)
            return 0;

        switch(mode)
        {
            case NORMAL:
                return (double)nodes[idx1].getBigramCount(c2)/nodes[idx1].getCount();
            case ADD_1:
                return (double)(nodes[idx1].getBigramCount(c2)+1)/(nodes[idx1].getCount()+dictionarySize);
            case ADD_K:
                return (double)(nodes[idx1].getBigramCount(c2)+ks[0])/(nodes[idx1].getCount()+ks[0]*dictionarySize);
            case LI_EL:
                if(nodes[idx1].getCount() == 0)
                    return 0;
                return 0.5*(unigramProb(c1)) + 0.5*((double)nodes[idx1].getBigramCount(c2)/nodes[idx1].getCount());
            case LI_TL:
                if(nodes[idx1].getCount() == 0)
                    return 0;
                return lambdas[0]*(unigramProb(c1)) + lambdas[1]*((double)nodes[idx1].getBigramCount(c2)/nodes[idx1].getCount());
            default: 
                return -1;
        }
    }

    private double trigramProb(int c1, int c2, int c3)
    {
        c1 = toUpper(c1);
        c2 = toUpper(c2);
        c3 = toUpper(c3);
        int idx1 = c1-OFFSET;
        if(nodes[idx1] == null || nodes[c2-OFFSET] == null || nodes[c3-OFFSET] == null)
            return 0;

        switch(mode)
        {
            case NORMAL:
                return (double)nodes[idx1].getTrigramCount(c2, c3)/nodes[idx1].getBigramCount(c2);
            case ADD_1:
                return (double)(nodes[idx1].getTrigramCount(c2, c3)+1)/(nodes[idx1].getBigramCount(c2)+dictionarySize);
            case ADD_K:
                return (double)(nodes[idx1].getTrigramCount(c2, c3)+ks[1])/(nodes[idx1].getBigramCount(c2)+ks[1]*dictionarySize);
            case LI_EL:
                double lambda = 1.0/3.0;
                if(nodes[idx1].getCount() == 0)
                    return 0;
                else if(nodes[idx1].getBigramCount(c2) == 0)
                    return lambda*unigramProb(c1);
                return lambda*unigramProb(c1) + lambda*((double)nodes[idx1].getBigramCount(c2)/nodes[idx1].getCount()) + lambda*((double)nodes[idx1].getTrigramCount(c2, c3)/nodes[idx1].getBigramCount(c2));
            case LI_TL:
                if(nodes[idx1].getCount() == 0)
                    return 0;
                else if(nodes[idx1].getBigramCount(c2) == 0)
                    return lambdas[2]*unigramProb(c1);
                return lambdas[2]*unigramProb(c1) + lambdas[3]*((double)nodes[idx1].getBigramCount(c2)/nodes[idx1].getCount()) + lambdas[4]*((double)nodes[idx1].getTrigramCount(c2, c3)/nodes[idx1].getBigramCount(c2));
            default: 
                return -1;
        }
    }

    public int totalChars(int character)
    {
        character = toUpper(character);
        if(nodes[character-OFFSET] ==  null)
            return 0;
        else
            return nodes[character-OFFSET].getCount();
    }

    public int totalChars()
    {
        return numChars;
    }

    // reconfigure model as one of 5 types: normal (0), add-1 smoothing (1), tuned add-k smoothing (2), 
    //      equal lambda linear interpolation (3), or tuned lambda linear interpolation (4)
    public boolean configure(int m)
    {
        if(m >= 0 && m <= 4)
        {
            mode = Mode.values()[m];
            if(mode == Mode.ADD_K)
                setTunedKs();
            if(mode == Mode.LI_TL)
                setTunedLambdas();
            return true;
        }
        return false;
    }

    // ------------------------- DEVSET METHODS -------------------------
    public boolean addToDevset(int c1)
    {
        c1 = toUpper(c1);
        if(devIdx >= devset.length)
        {
            System.err.println("ERROR: Devset is full!");
            return false;
        }

        devset[devIdx] = c1;
        devIdx++;

        return true;
    }

    // local method, returns natural-logged probabilities for devset in a double array up to n-gram
    private double[] getDevsetProbs(int n)
    {
        double[] probs = new double[n-1];
        int[] c = new int[n];

        for(int d = 0; d < devset.length-1; d++)
        {
            for(int i = 1; i <= n; i++)
            {
                if(d < devset.length-(i-1))
                    c[i-1] = devset[d+i-1];
            }
            
            probs[0] += Math.log(bigramProb(c[0], c[1]));
            if(d < devset.length-2)
                probs[1] += Math.log(trigramProb(c[0], c[1], c[2]));
        }

        return probs;
    }

    // gets and assigns best values based on highest probabilities wtih 2 modes, mode = 0 (ks), mode = 1 (outer lambdas), or mode = 2 (inner lambdas)
    private void getBestVals(int mode, double[] bestVals, double step, double[] bestP, double[] upP, double[] downP)
    {
        for(int i = 0; i < bestP.length; i++)
        {
            if(upP[i] > bestP[i] && upP[i] > downP[i])
            {
                bestP[i] = upP[i];
                if(mode == 0)
                    bestVals[i] += step;
                // change lambda values
                else
                {
                    if(i == 0)
                    {
                        bestVals[0] += step;
                        bestVals[1] = 1 - bestVals[0]; 
                    }
                    else if (i == 1)
                    {
                        bestVals[2] += step;
                        bestVals[3] -= step/2;
                        bestVals[4] = 1 - bestVals[2] - bestVals[3];
                        if(bestVals[4] < 0)
                        {
                            bestVals[2] += bestVals[4];
                            bestVals[4] = 0;
                        }
                        if(bestVals[3] < 0)
                        {
                            bestVals[2] += bestVals[3];
                            bestVals[3] = 0;
                        }
                    }
                    else if (i == 2)
                    {
                        bestVals[3] += step;
                        bestVals[4] = 1 - bestVals[2] - bestVals[3];
                        if(bestVals[4] < 0)
                        {
                            bestVals[3] += bestVals[4];
                            bestVals[4] = 0;
                        }
                    }
                }
            }
            else if(downP[i] > bestP[i] && downP[i] > upP[i])
            {
                bestP[i] = downP[i];
                if(mode == 0)
                    bestVals[i] -= step;
                //change lambda values
                else
                {
                    if(i == 0)
                    {
                        bestVals[0] -= step;
                        bestVals[1] = 1 - bestVals[0]; 
                    }
                    else if (i == 1)
                    {
                        bestVals[2] -= step;
                        bestVals[3] += step/2;
                        bestVals[4] = 1 - bestVals[2] - bestVals[3];
                        if(bestVals[2] < 0)
                        {
                            bestVals[3] += bestVals[2]/2;
                            bestVals[4] = 1 - bestVals[2] - bestVals[3];
                            bestVals[2] = 0;
                        }
                    }
                    else if (i == 2)
                    {
                        bestVals[3] -= step;
                        bestVals[4] = 1 - bestVals[2] - bestVals[3];
                        if(bestVals[3] < 0)
                        {
                            bestVals[4] += bestVals[3];
                            bestVals[3] = 0;
                        }
                    }
                }
            }
        }
    }

    private void setTunedKs()
    {
        Random rand = new Random();
        double step;
        double[] bestProbs = new double[2];
        double[] upProbs = new double[2];
        double[] downProbs = new double[2];
        double[] bestKs = new double[2];

        // start all tuned k values at 0.5
        //  bestKs[0] = 0.5;
        //  bestKs[1] = 0.5;
        bestKs[0] = 0.4 + rand.nextDouble() * 0.2;
        bestKs[1] = 0.4 + rand.nextDouble() * 0.2;
        ks[0] = bestKs[0];
        ks[1] = bestKs[1];
        bestProbs = getDevsetProbs(3);

        for(int i = 0; i < TUNING_ITERATIONS; i++)
        {
            step = 0.25 / Math.pow(2, i);
            
            // check prob value for ks tuned up
            ks[0] = bestKs[0] + step;
            ks[1] = bestKs[1] + step;
            upProbs = getDevsetProbs(3);

            // check prob value for ks tuned down
            ks[0] = bestKs[0] - step;
            ks[1] = bestKs[1] - step;
            downProbs = getDevsetProbs(3);

            getBestVals(0, bestKs, step, bestProbs, upProbs, downProbs);
            ks[0] = bestKs[0];
            ks[1] = bestKs[1];
        }
    }

    //  Returns set of tuned lambdas such that sum(lambdas)=1 with size of 5,
    //      0-1 for bigram, 2-4 for trigram
    private void setTunedLambdas()
    {
        double step;
        double[] bestProbs = new double[2];
        double[] upProbs = new double[2];
        double[] downProbs = new double[2];
        double[] bestLs = new double[5];
        
        // set initial tuned lambda values
        bestLs[0] = 0.5;
        bestLs[1] = 0.5;
        bestLs[2] = 0.33;
        bestLs[3] = 0.33;
        bestLs[4] = 0.34;
        for(int l = 0; l < 5; l++)
            lambdas[l] = bestLs[l];
        bestProbs = getDevsetProbs(3);

        // outer step changes lambdas[0] and lambdas[2], inner step changes lambdas[3], lambdas[1] and lambdas[4] are inferred
        for(int i = 0; i < TUNING_ITERATIONS; i++)
        {
            step = 0.25 / Math.pow(2, i);

            // ----------------------------------- TUNE OUTER LAMBDAS --------------------------------
            // check prob value for outer ls tuned up
            lambdas[0] = bestLs[0] + step;
            lambdas[1] = 1-lambdas[0];
            lambdas[2] = bestLs[2] + step;
            lambdas[3] = bestLs[3] - step/2;
            lambdas[4] = 1 - lambdas[2] - lambdas[3];
            if(lambdas[4] < 0)
            {
                lambdas[2] += lambdas[4];
                lambdas[4] = 0;
            }
            if(lambdas[3] < 0)
            {
                lambdas[2] += lambdas[3];
                lambdas[3] = 0;
            }
            upProbs = getDevsetProbs(3);

            // check prob value for outer ls tuned down
            lambdas[0] = bestLs[0] - step;
            lambdas[1] = 1-lambdas[0];
            lambdas[2] = bestLs[2] - step;
            lambdas[3] = bestLs[3] + step/2;
            lambdas[4] = 1 - lambdas[2] - lambdas[3];
            if(lambdas[2] < 0)
            {
                lambdas[3] += lambdas[2]/2;
                lambdas[4] = 1 - lambdas[2] - lambdas[3];
                lambdas[2] = 0;
            }
            downProbs = getDevsetProbs(3);

            getBestVals(1, bestLs, step, bestProbs, upProbs, downProbs);
            for(int l = 0; l < 5; l++)
                lambdas[l] = bestLs[l];
            // ----------------------------------- TUNE OUTER LAMBDAS --------------------------------

            // ----------------------------------- TUNE INNER LAMBDAS --------------------------------
            // check prob value for inner ls tuned up
            lambdas[3] = bestLs[3] + step;
            lambdas[4] = 1 - lambdas[2] - lambdas[3];
            if(lambdas[4] < 0)
            {
                lambdas[3] += lambdas[4];
                lambdas[4] = 0;
            }
            upProbs = getDevsetProbs(3);

            // check prob value for inner ls tuned down
            lambdas[3] = bestLs[3] - step;
            lambdas[4] = 1 - lambdas[2] - lambdas[3];
            if(lambdas[3] < 0)
            {
                lambdas[4] += lambdas[3];
                lambdas[3] = 0;
            }
            downProbs = getDevsetProbs(3);

            getBestVals(2, bestLs, step, bestProbs, upProbs, downProbs);
            for(int l = 0; l < 5; l++)
                lambdas[l] = bestLs[l];
            // ----------------------------------- TUNE INNER LAMBDAS --------------------------------
        }
    }
    // ------------------------- DEVSET METHODS -------------------------

    public double[] getPerplexity(int[] sentence, int size)
    {
        double[] perplexities = {1.0, 1.0, 1.0};

        for(int i = 0; i < size; i++)
        {
            perplexities[0] *= Math.pow(1/unigramProb(toUpper(sentence[i])), 1.0/size);
            if(i < size-1)
                perplexities[1] *= Math.pow(1/bigramProb(toUpper(sentence[i]), toUpper(sentence[i+1])), 1.0/size);
            if(i < size-2)
                perplexities[2] *= Math.pow(1/trigramProb(toUpper(sentence[i]), toUpper(sentence[i+1]), toUpper(sentence[i+2])), 1.0/size);
        }

        return perplexities;
    }

    public int getNextLetter(int c1)
    {
        Random rand = new Random();
        double totalProb = 0;
        double[] runningProbs = new double[SIZE+1];
        double val;

        c1 = toUpper(c1);
        int nextChar = -1;
        double maxProb = 0;
        double currProb = 0;

        for (int i = OFFSET; i < SIZE+OFFSET; i++)
        {
            currProb = bigramProb(c1, i);
            // if(currProb > maxProb)
            // {
            //     maxProb = currProb;
            //     nextChar = i;
            // }
            totalProb += currProb;
            runningProbs[i-OFFSET+1] = runningProbs[i-OFFSET] + currProb;
        }

        val = rand.nextDouble() * totalProb;
        for(int i = 0; i < SIZE; i++)
        {
            if(val > runningProbs[i])
                nextChar = i+OFFSET;
        }
        return nextChar;
    }

    public int getNextLetter(int c1, int c2)
    {
        Random rand = new Random();
        double totalProb = 0;
        double[] runningProbs = new double[SIZE+1];
        double val;

        c1 = toUpper(c1);
        c2 = toUpper(c2);
        int nextChar = -1;
        double maxProb = 0;
        double currProb = 0;
        for (int i = OFFSET; i < SIZE+OFFSET; i++)
        {
            currProb = trigramProb(c1, c2, i);
            // if(currProb > maxProb)
            // {
            //     maxProb = currProb;
            //     nextChar = i;
            // }
            totalProb += currProb;
            runningProbs[i-OFFSET+1] = runningProbs[i-OFFSET] + currProb;
        }

        val = rand.nextDouble() * totalProb;
        for(int i = 0; i < SIZE; i++)
        {
            if(val > runningProbs[i])
                nextChar = i+OFFSET;
        }
        return nextChar;
    }

    //public String toString()
    public void printToFile(FileWriter fout) throws IOException
    {
        try {
            //String str = "";
            int bi, tri;
            double uniChecksum = 0.0, biChecksum = 0.0, triChecksum = 0.0;

            //str += "UNIGRAM MODEL: " + dictionarySize + " distinct characters, " + totalChars() + " total characters\n";
            fout.write("UNIGRAM MODEL: " + dictionarySize + " distinct characters, " + totalChars() + " total characters\n");

            for(int i = 0; i < SIZE; i++)
            {
                if(nodes[i] != null)
                {
                    //str += (char)(i+OFFSET) + ": " + unigramProb(i+OFFSET) + " (" + nodes[i].getCount() + ")\n";
                    fout.write((char)(i+OFFSET) + ": " + unigramProb(i+OFFSET) + " (" + nodes[i].getCount() + ")\n");
                    uniChecksum += unigramProb(i+OFFSET);
                }
            }
            fout.write("Unigram checksum is " + uniChecksum);

            //str += "\n\n\nBIGRAM MODEL: ";
            fout.write("\n\n\nBIGRAM MODEL: ");
            if(mode == Mode.ADD_K) 
                //str += "k = " + ks[0];
                fout.write("k = " + ks[0]);
            else if(mode == Mode.LI_TL)
                //str += "lambda1 = " + lambdas[0] + ", lambda2 = " + lambdas[1];
                fout.write("lambda1 = " + lambdas[0] + ", lambda2 = " + lambdas[1]);
            //str += "\n";
            fout.write("\n");

            for(int i = 0; i < SIZE; i++)
            {
                if(nodes[i] != null)
                {
                    //str += "\t" + (char)(i+OFFSET) + ": " + totalChars(i+OFFSET) + "\n";
                    fout.write("\t" + (char)(i+OFFSET) + ": " + totalChars(i+OFFSET) + "\n");
                    biChecksum = 0;
                    for(int j = 0; j < SIZE; j++)
                    {
                        if(nodes[j] != null)
                        {
                            bi = nodes[i].getBigramCount(j+OFFSET);
                            if(bi != 0 || mode.ordinal() > 0)
                            {
                                //str += "\t\t" + (char)(i+OFFSET) + "" + (char)(j+OFFSET) + ": " + bigramProb(i+OFFSET, j+OFFSET) + " (" + bi + ")\n";
                                fout.write("\t\t" + (char)(i+OFFSET) + "" + (char)(j+OFFSET) + ": " + bigramProb(i+OFFSET, j+OFFSET) + " (" + bi + ")\n");
                                biChecksum += bigramProb(i+OFFSET, j+OFFSET);
                            }
                        }
                    }
                    if(mode.ordinal() < 3)
                        fout.write("\tBigram checksum for " + (char)(i+OFFSET) + " is " + biChecksum + "\n\n");
                    else
                        fout.write("\n");
                }
            }

            // Doubles used to accelerate trigram process
            double triProb;

            //str += "\n\n\nTRIGRAM MODEL: ";
            fout.write("\n\n\nTRIGRAM MODEL: ");
            if(mode == Mode.ADD_K) 
                //str += "k = " + ks[1];
                fout.write("k = " + ks[1]);
            else if(mode == Mode.LI_TL)
                //str += "lambda1 = " + lambdas[2] + ", lambda2 = " + lambdas[3] + ", lambda3 = " + lambdas[4];
                fout.write("lambda1 = " + lambdas[2] + ", lambda2 = " + lambdas[3] + ", lambda3 = " + lambdas[4]);
            //str += "\n";
            fout.write("\n");

            for(int i = 0; i < SIZE; i++)
            {
                if(nodes[i] != null)
                {
                    for(int j = 0; j < SIZE; j++)
                    {
                        if(nodes[j] != null)
                        {
                            bi = nodes[i].getBigramCount(j+OFFSET);
                            if(bi != 0 || mode.ordinal() > 0)
                            {
                                //str += "\t" + (char)(i+OFFSET) + "" + (char)(j+OFFSET) + ": " + bi + "\n";
                                fout.write("\t" + (char)(i+OFFSET) + "" + (char)(j+OFFSET) + ": " + bi + "\n");
                                triChecksum = 0;
                                for(int k = 0; k < SIZE; k++)
                                {
                                    if(nodes[k] != null)
                                    {
                                        tri = nodes[i].getTrigramCount(j+OFFSET, k+OFFSET);
                                        if(tri != 0 || mode.ordinal() > 0)
                                        {          
                                            //triProb = getNume(i+OFFSET, j+OFFSET, k+OFFSET)/denom + extra;                          
                                            triProb = trigramProb(i+OFFSET, j+OFFSET, k+OFFSET);
                                            //str += "\t\t" + (char)(i+OFFSET) + "" + (char)(j+OFFSET) + "" + (char)(k+OFFSET) + ": " + triProb + " (" + tri + ")\n";
                                            fout.write("\t\t" + (char)(i+OFFSET) + "" + (char)(j+OFFSET) + "" + (char)(k+OFFSET) + ": " + triProb + " (" + tri + ")\n");
                                            triChecksum += triProb;
                                        }
                                    }
                                }
                                if(mode.ordinal() < 3)
                                    fout.write("\tTrigram checksum for " + (char)(i+OFFSET) + "" + (char)(j+OFFSET) + " is " + triChecksum + "\n\n");
                                else
                                    fout.write("\n");
                            }
                        }
                    }
                }
            }
            //str += "Unigram checksum is " + uniChecksum + "\nBigram checksum is " + biChecksum + "\nTrigram checksum is " + triChecksum + "\n";
            //return str;
        } catch (IOException e) {
            System.err.println(e);
        }
    }

    private class NGramNode
    {
        private int value;
        private int count;
        private int[] bigram;
        private int[][] trigram;

        private NGramNode(int val, int ct)
        {
            value = val;
            count = ct;
            bigram = new int[SIZE];
            trigram = new int[SIZE][SIZE];
        }

        private boolean addSeq(int bi, int tri)
        {
            count++;
            if(bi != -1)
                bigram[bi-OFFSET]++;
            if(tri != -1)
                trigram[bi-OFFSET][tri-OFFSET]++;
            return true;
        }

        private int getCount()
        {
            return count;
        }

        private int getBigramCount(int character)
        {
            return bigram[character-OFFSET];
        }

        private int getTrigramCount(int c2, int c3)
        {
            return trigram[c2-OFFSET][c3-OFFSET];
        }
    }
}