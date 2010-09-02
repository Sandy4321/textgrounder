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
package opennlp.textgrounder.bayesian.spherical.annealers;

import opennlp.textgrounder.bayesian.annealers.Annealer;
import opennlp.textgrounder.bayesian.apps.ExperimentParameters;
import opennlp.textgrounder.bayesian.mathutils.TGBLAS;

/**
 *
 * @author Taesun Moon <tsunmoon@gmail.com>
 */
public abstract class SphericalAnnealer extends Annealer {

    /**
     * 
     */
    protected int geoMeanVecLen;
    /**
     *
     */
    protected double[] allWordsRegionCounts;
    /**
     * Counts of tcount per topic. However, since access more often occurs in
     * terms of the tcount, it will be a topic by word matrix.
     */
    protected double[] wordByRegionCounts;
    /**
     * 
     */
    protected double[] regionByDocumentCounts;
    /**
     *
     */
//    protected double[] toponymByRegionCounts;
    /**
     *
     */
    protected double[][][] regionToponymCoordinateCounts;
    /**
     * 
     */
    protected double[][] regionMeans;

    /**
     *
     */
//    protected double[] nonToponymRegionCounts;
    protected SphericalAnnealer() {
    }

    public SphericalAnnealer(ExperimentParameters _experimentParameters) {
        super(_experimentParameters);
    }

    public void collectSamples(int[] _wordByRegionCounts, int[] _regionByDocumentCounts,
          int[] _allWordsRegionCounts, double[][] _regionMeans,
          //          int[] _toponymByRegionCounts, int[] _nonToponymRegionCounts,
          int[][][] _regionToponymCoordinateCounts) {

        if (sampleCount < samples) {
            if (sampleiteration && (innerIter % lag == 0)) {
                sampleCount += 1;
                if (samples == sampleCount) {
                    finishedCollection = true;
                }

                System.err.print("(sample:" + (innerIter + 1) / lag + ")");
                if (wordByRegionCounts == null) {
                    wordByRegionCounts = new double[_wordByRegionCounts.length];
                    regionByDocumentCounts = new double[_regionByDocumentCounts.length];
                    allWordsRegionCounts = new double[_allWordsRegionCounts.length];
//                    toponymByRegionCounts = new double[_toponymByRegionCounts.length];
//                    nonToponymRegionCounts = new double[_nonToponymRegionCounts.length];
                    for (int i = 0; i < _wordByRegionCounts.length; ++i) {
                        wordByRegionCounts[i] = 0;
                    }

                    for (int i = 0; i < _regionByDocumentCounts.length; ++i) {
                        regionByDocumentCounts[i] = 0;
                    }

                    for (int i = 0; i < allWordsRegionCounts.length; ++i) {
                        allWordsRegionCounts[i] = 0;
                    }

//                    for (int i = 0; i < _toponymByRegionCounts.length; ++i) {
//                        toponymByRegionCounts[i] = 0;
//                    }
//                    for (int i = 0; i < _nonToponymRegionCounts.length; ++i) {
//                        _nonToponymRegionCounts[i] = 0;
//                    }

                    regionToponymCoordinateCounts = new double[_regionToponymCoordinateCounts.length][][];
                    for (int i = 0; i < _regionToponymCoordinateCounts.length; ++i) {
                        regionToponymCoordinateCounts[i] = new double[_regionToponymCoordinateCounts[i].length][];
                        for (int j = 0; j < _regionToponymCoordinateCounts[i].length; ++j) {
                            regionToponymCoordinateCounts[i][j] = new double[_regionToponymCoordinateCounts[i][j].length];
                            for (int k = 0; k < _regionToponymCoordinateCounts[i][j].length; ++k) {
                                regionToponymCoordinateCounts[i][j][k] = 0;
                            }
                        }
                    }

                    regionMeans = new double[_regionMeans.length][];
                    geoMeanVecLen = _regionMeans[0].length;
                    for (int i = 0; i < _regionMeans.length; ++i) {
                        double[] mean = new double[geoMeanVecLen];
                        for (int j = 0; j < geoMeanVecLen; ++j) {
                            mean[j] = 0;
                        }
                        regionMeans[i] = mean;
                    }
                }

                for (int i = 0; i < wordByRegionCounts.length; ++i) {
                    wordByRegionCounts[i] += _wordByRegionCounts[i];
                }

                for (int i = 0; i < regionByDocumentCounts.length; ++i) {
                    regionByDocumentCounts[i] += _regionByDocumentCounts[i];
                }

                for (int i = 0; i < allWordsRegionCounts.length; ++i) {
                    allWordsRegionCounts[i] = _allWordsRegionCounts[i];
                }

//                for (int i = 0; i < toponymByRegionCounts.length; ++i) {
//                    toponymByRegionCounts[i] += _toponymByRegionCounts[i];
//                }
//
//                for (int i = 0; i < nonToponymRegionCounts.length; ++i) {
//                    nonToponymRegionCounts[i] += _nonToponymRegionCounts[i];
//                }

                for (int i = 0; i < regionToponymCoordinateCounts.length; ++i) {
                    for (int j = 0; j < regionToponymCoordinateCounts[i].length; ++j) {
                        for (int k = 0; k < regionToponymCoordinateCounts[i][j].length; ++k) {
                            regionToponymCoordinateCounts[i][j][k] += _regionToponymCoordinateCounts[i][j][k];
                        }
                    }
                }

                for (int i = 0; i < regionMeans.length; ++i) {
                    TGBLAS.daxpy(geoMeanVecLen, 1, _regionMeans[i], 1, regionMeans[i], 1);
                }
            }
            if (finishedCollection) {
                normalizeSamples();
            }
        }
    }

    protected void normalizeSamples() {
        for (int i = 0; i < wordByRegionCounts.length; ++i) {
            wordByRegionCounts[i] /= sampleCount;
        }

        for (int i = 0; i < regionByDocumentCounts.length; ++i) {
            regionByDocumentCounts[i] /= sampleCount;
        }

        for (int i = 0; i < allWordsRegionCounts.length; ++i) {
            allWordsRegionCounts[i] /= sampleCount;
        }

//        for (int i = 0; i < toponymByRegionCounts.length; ++i) {
//            toponymByRegionCounts[i] /= sampleCount;
//        }
//
//        for (int i = 0; i < nonToponymRegionCounts.length; ++i) {
//            nonToponymRegionCounts[i] /= sampleCount;
//        }

        for (int i = 0; i < regionToponymCoordinateCounts.length;
              ++i) {
            for (int j = 0;
                  j < regionToponymCoordinateCounts[i].length; ++j) {
                for (int k = 0;
                      k < regionToponymCoordinateCounts[i][j].length;
                      ++k) {
                    regionToponymCoordinateCounts[i][j][k] /= sampleCount;
                }
            }
        }

        for (int i = 0; i < regionMeans.length; ++i) {
            for (int j = 0; j < geoMeanVecLen; ++j) {
                regionMeans[i][j] /= sampleCount;
            }
        }
    }

    public double[] getRegionByDocumentCounts() {
        return regionByDocumentCounts;
    }

    public double[][][] getRegionToponymCoordinateCounts() {
        return regionToponymCoordinateCounts;
    }

    public double[] getWordByRegionCounts() {
        return wordByRegionCounts;
    }

    public void setRegionByDocumentCounts(double[] _regionByDocumentCounts) {
        regionByDocumentCounts = _regionByDocumentCounts;
    }

    public void setRegionToponymCoordinateCounts(double[][][] _regionToponymCoordinateCounts) {
        regionToponymCoordinateCounts = _regionToponymCoordinateCounts;
    }

    public void setWordByRegionCounts(double[] _wordByRegionCounts) {
        wordByRegionCounts = _wordByRegionCounts;
    }

    public double[] getAllWordsRegionCounts() {
        return allWordsRegionCounts;
    }

    public void setAllWordsRegionCounts(double[] _allWordsRegionCounts) {
        allWordsRegionCounts = _allWordsRegionCounts;
    }

    public double[][] getRegionMeans() {
        return regionMeans;
    }

    public void setRegionMeans(double[][] _regionMeans) {
        regionMeans = _regionMeans;
    }
}
