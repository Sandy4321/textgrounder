///////////////////////////////////////////////////////////////////////////////
//  Copyright 2010 Taesun Moon <tsunmoon@gmail.com>.
// 
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
// 
//       http://www.apache.org/licenses/LICENSE-2.0
// 
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//  under the License.
///////////////////////////////////////////////////////////////////////////////
package opennlp.rlda.models;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import opennlp.rlda.annealers.*;
import opennlp.rlda.apps.ExperimentParameters;
import opennlp.rlda.ec.util.MersenneTwisterFast;

/**
 *
 * @author Taesun Moon <tsunmoon@gmail.com>
 */
public class RegionModel extends RegionModelFields {

    /**
     * Random number generator. Implements the fast Mersenne Twister.
     */
    protected transient MersenneTwisterFast rand;
    /**
     * Handles simulated annealing, burn-in, and full sampling cycle
     */
    protected transient Annealer annealer;
    /**
     * 
     */
    protected ExperimentParameters experimentParameters;

    /**
     * Default constructor. Take input from commandline and default _options
     * and initialize class. Also, process input text and process so that
     * toponyms, stopwords and other words are identified and collected.
     *
     * @param _options
     */
    public RegionModel(ExperimentParameters _parameters) {
        experimentParameters = _parameters;
    }

    /**
     *
     * @param _options
     */
    protected final void initialize(ExperimentParameters _parameters) {
        readTokenFile(_parameters.getTrainInputPath());
        alpha = _parameters.getAlpha();
        beta = _parameters.getBeta();
    }

    public void initialize() {
        initialize(experimentParameters);
    }

    public void readTokenFile(String _filename) {
        readTokenFile(new File(_filename));
    }

    public void readTokenFile(File _file) {
        BufferedReader textIn = null;
        try {
            textIn = new BufferedReader(new FileReader(_file));
        } catch (FileNotFoundException ex) {
            Logger.getLogger(RegionModel.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }

        HashSet<Integer> stopwordSet = new HashSet<Integer>();
        ArrayList<Integer> wordArray = new ArrayList<Integer>(),
              docArray = new ArrayList<Integer>(),
              toponymArray = new ArrayList<Integer>(),
              stopwordArray = new ArrayList<Integer>();

        try {
            String line = null;
            while ((line = textIn.readLine()) != null) {
                String[] fields = line.split("\\w+");
                if (fields.length > 2) {
                    int wordidx = Integer.parseInt(fields[0]);
                    wordArray.add(wordidx);
                    int docidx = Integer.parseInt(fields[1]);
                    docArray.add(docidx);
                    toponymArray.add(Integer.parseInt(fields[2]));
                    try {
                        stopwordArray.add(Integer.parseInt(fields[3]));
                        stopwordSet.add(wordidx);
                    } catch (ArrayIndexOutOfBoundsException e) {
                    }
                    if (W < wordidx) {
                        W = wordidx;
                    }
                    if (D < docidx) {
                        D = docidx;
                    }
                }
            }
        } catch (IOException ex) {
            Logger.getLogger(RegionModel.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }

        W -= stopwordSet.size();
        N = wordArray.size();

        wordVector = new int[N];
        copyToArray(wordVector, wordArray);

        documentVector = new int[N];
        copyToArray(documentVector, docArray);

        toponymVector = new int[N];
        copyToArray(toponymVector, toponymArray);

        stopwordVector = new int[N];
        if (stopwordArray.size() == N) {
            copyToArray(stopwordVector, stopwordArray);
        } else {
            for (int i = 0; i < N; ++i) {
                stopwordVector[i] = 0;
            }
        }

        regionVector = new int[N];
    }

    /**
     *
     * @param _filename
     */
    public void readRegionToponymFilter(String _filename) {
        readRegionToponymFilter(new File(_filename));
    }

    /**
     * 
     * @param _file
     */
    public void readRegionToponymFilter(File _file) {
        BufferedReader textin = null;
        try {
            textin = new BufferedReader(new FileReader(_file));
        } catch (FileNotFoundException ex) {
            Logger.getLogger(RegionModel.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }

        String line = null;

        regionByToponymFilter = new int[R * W];
        for (int i = 0; i < R * W; ++i) {
            regionByToponymFilter[i] = 0;
        }

        try {
            while ((line = textin.readLine()) != null) {
                if (!line.isEmpty()) {
                    String[] fields = line.split("\\w+");
                    int wordoff = Integer.parseInt(fields[0]) * R;
                    for (int i = 1; i < fields.length; ++i) {
                        regionByToponymFilter[wordoff + i] = 1;
                    }
                }
            }
        } catch (IOException ex) {
            Logger.getLogger(RegionModel.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }
    }

    /**
     * Randomly initialize fields for training. If word is a toponym, choose
     * random region only from regions aligned to name.
     */
    public void randomInitialize() {
        int wordid, docid, regionid;
        int istoponym, isstopword;
        int wordoff, docoff;
        double[] probs = new double[R];
        double totalprob, max, r;

        for (int i = 0; i < N; ++i) {
            isstopword = stopwordVector[i];
            if (isstopword == 0) {
                wordid = wordVector[i];
                docid = documentVector[i];
                docoff = docid * R;
                istoponym = toponymVector[i];

                totalprob = 0;
                if (istoponym == 1) {
                    wordoff = wordid * R;
                    try {
                        for (int j = 0;; ++j) {
                            totalprob += probs[j] =
                                  regionByToponymFilter[wordoff + j]
                                  * activeRegionByDocumentFilter[docoff + j];
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                    }
                } else {
                    try {
                        for (int j = 0;; ++j) {
                            totalprob += probs[j] =
                                  activeRegionByDocumentFilter[docoff + j];
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                    }
                }

                r = rand.nextDouble() * totalprob;

                max = probs[0];
                regionid = 0;
                while (r > max) {
                    regionid++;
                    max += probs[regionid];
                }

                regionVector[i] = regionid;
                regionCounts[regionid]++;
                regionByDocumentCounts[docid * R + regionid]++;
                wordByRegionCounts[wordid * R + regionid]++;
            }
        }
    }

    /**
     * Train topics
     *
     * @param annealer Annealing scheme to use
     */
    public void train(Annealer annealer) {
        int wordid, docid, regionid;
        int wordoff, docoff;
        int istoponym, isstopword;
        double[] probs = new double[R];
        double totalprob, max, r;

        while (annealer.nextIter()) {
            for (int i = 0; i < N; ++i) {
                isstopword = stopwordVector[i];
                if (isstopword == 0) {
                    wordid = wordVector[i];
                    docid = documentVector[i];
                    regionid = regionVector[i];
                    istoponym = toponymVector[i];
                    docoff = docid * R;
                    wordoff = wordid * R;

                    regionCounts[regionid]--;
                    regionByDocumentCounts[docoff + regionid]--;
                    wordByRegionCounts[wordoff + regionid]--;

                    try {
                        if (istoponym == 1) {
                            for (int j = 0;; ++j) {
                                probs[j] = (wordByRegionCounts[wordoff + j] + beta)
                                      / (regionCounts[j] + betaW)
                                      * (regionByDocumentCounts[docoff + j] + alpha)
                                      * regionByToponymFilter[wordoff + j]
                                      * activeRegionByDocumentFilter[docoff + j];
                            }
                        } else {
                            for (int j = 0;; ++j) {
                                probs[j] = (wordByRegionCounts[wordoff + j] + beta)
                                      / (regionCounts[j] + betaW)
                                      * (regionByDocumentCounts[docoff + j] + alpha)
                                      * activeRegionByDocumentFilter[docoff + j];
                            }
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                    }
                    totalprob = annealer.annealProbs(probs);
                    r = rand.nextDouble() * totalprob;

                    max = probs[0];
                    regionid = 0;
                    while (r > max) {
                        regionid++;
                        max += probs[regionid];
                    }
                    regionVector[i] = regionid;

                    regionCounts[regionid]++;
                    regionByDocumentCounts[docoff + regionid]++;
                    wordByRegionCounts[wordoff + regionid]++;
                }
            }

            annealer.collectSamples(regionCounts, wordByRegionCounts);
        }
    }

    public void train() {
        System.err.println(String.format("Randomly initializing with %d tokens, %d words, %d regions, %d documents", N, W, R, D));
        randomInitialize();
        System.err.println(String.format("Beginning training with %d tokens, %d words, %d regions, %d documents", N, W, R, D));
        train(annealer);
        if (annealer.getSamples() != 0) {
            regionProbs = annealer.getTopicSampleCounts();
            wordByRegionProbs = annealer.getWordByTopicSampledProbs();
        }
    }

    public void normalize() {
    }

    /**
     *
     * @param _outputFilename
     * @throws IOException
     */
    public void saveModel(String _outputFilename) throws IOException {
        ObjectOutputStream modelOut =
              new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(_outputFilename + ".gz")));
        modelOut.writeObject(this);
        modelOut.close();
    }

//    /**
//     *
//     * @param _inputFilename
//     * @throws IOException
//     */
//    public void loadModel(String _inputFilename) throws IOException {
//        ObjectInputStream modelIn =
//              new ObjectInputStream(new GZIPInputStream(new FileInputStream(_inputFilename)));
//        try {
//            this = (RegionModel) modelIn.readObject();
//        } catch (ClassNotFoundException ex) {
//            Logger.getLogger(RegionModel.class.getName()).log(Level.SEVERE, null, ex);
//        }
//    }
    /**
     * Copy a sequence of numbers from ta to array ia.
     *
     * @param <T>   Any number type
     * @param ia    Target array of integers to be copied to
     * @param ta    Source List<T> of numbers to be copied from
     */
    protected static <T extends Number> void copyToArray(int[] ia,
          ArrayList<T> ta) {
        for (int i = 0; i < ta.size(); ++i) {
            ia[i] = ta.get(i).intValue();
        }
    }
}
