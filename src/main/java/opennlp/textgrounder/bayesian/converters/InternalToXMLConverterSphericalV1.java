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
package opennlp.textgrounder.bayesian.converters;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import opennlp.textgrounder.bayesian.apps.ConverterExperimentParameters;
import opennlp.textgrounder.bayesian.mathutils.TGMath;
import opennlp.textgrounder.bayesian.topostructs.*;
import opennlp.textgrounder.bayesian.spherical.io.*;
import opennlp.textgrounder.bayesian.structs.AveragedSphericalCountWrapper;
import opennlp.textgrounder.bayesian.wrapper.io.*;
import org.jdom.Element;

/**
 *
 * @author Taesun Moon <tsunmoon@gmail.com>
 */
public class InternalToXMLConverterSphericalV1 extends InternalToXMLConverter {

    /**
     * 
     */
    double[][][] toponymCoordinateLexicon;
    /**
     * 
     */
    double[][] regionMeans;
    /**
     *
     */
    double[] kappa;
    /**
     *
     */
    protected SphericalInternalToInternalInputReader inputReader;
    /**
     * 
     */
    ArrayList<Integer> coordArray;

    /**
     *
     * @param _converterExperimentParameters
     */
    public InternalToXMLConverterSphericalV1(
          ConverterExperimentParameters _converterExperimentParameters) {
        super(_converterExperimentParameters);
        InputReader reader = new BinaryInputReader(_converterExperimentParameters);
        lexicon = reader.readLexicon();

        inputReader = new SphericalInternalToInternalBinaryInputReader(_converterExperimentParameters);
    }

    /**
     *
     */
    public void readCoordinateList() {
        AveragedSphericalCountWrapper ascw = inputReader.readProbabilities();

        regionMeans = ascw.getRegionMeansFM();
        kappa = ascw.getKappaFM();
        toponymCoordinateLexicon = ascw.getToponymCoordinateLexicon();
    }

    @Override
    public void initialize() {
        readCoordinateList();

        wordArray = new ArrayList<Integer>();
        docArray = new ArrayList<Integer>();
        toponymArray = new ArrayList<Integer>();
        stopwordArray = new ArrayList<Integer>();
        regionArray = new ArrayList<Integer>();
        coordArray = new ArrayList<Integer>();

        try {
            while (true) {
                int[] record = inputReader.nextTokenArrayRecord();
                if (record != null) {
                    int wordid = record[0];
                    wordArray.add(wordid);
                    int docid = record[1];
                    docArray.add(docid);
                    int topstatus = record[2];
                    toponymArray.add(topstatus);
                    int stopstatus = record[3];
                    stopwordArray.add(stopstatus);
                    int regid = record[4];
                    regionArray.add(regid);
                    int coordid = record[5];
                    coordArray.add(coordid);
                }
            }
        } catch (EOFException ex) {
        } catch (IOException ex) {
            Logger.getLogger(InternalToXMLConverterSphericalV1.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    protected void setTokenAttribute(Element _token, int _wordid, int _regid, int _coordid) {
        return;
    }

    protected void setToponymAttribute(ArrayList<Element> _candidates, Element _token, int _wordid, int _regid, int _coordid) {
        _coordid = coordArray.get(offset);
        if (!_candidates.isEmpty()) {
            Coordinate coord = new Coordinate(TGMath.cartesianToGeographic(toponymCoordinateLexicon[_wordid][_coordid]));
            _token.setAttribute("long", String.format("%.6f", coord.longitude));
            _token.setAttribute("lat", String.format("%.6f", coord.latitude));
        }
    }

    @Override
    public void confirmCoordinate(double _long, double _lat) {
        int _coordid = coordArray.get(offset);
        Coordinate coord = new Coordinate(TGMath.cartesianToGeographic(toponymCoordinateLexicon[currentWordID][_coordid]));

        return;
    }

    @Override
    public void setCurrentWord(String _string) {
        currentWord = _string;
        currentWordID = lexicon.addOrGetWord(_string);
    }

    @Override
    public void incrementOffset() {
        offset += 1;
    }

    @Override
    protected void setToponymAttribute(ArrayList<Element> _candidates, Element _token, int _wordid, int _regid, int _coordid, int _offset) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setTokenAttribute(XMLStreamReader in, XMLStreamWriter out) throws XMLStreamException {
        int isstopword = stopwordArray.get(offset);
        int wordid = wordArray.get(offset);

        if (isstopword == 0) {
            String word = in.getAttributeValue(null, "tok");
            String outword = lexicon.getWordForInt(wordid);
            int coordid = coordArray.get(offset);
            if (word.toLowerCase().equals(outword)) {
                int regid = regionArray.get(offset);

                out.writeAttribute("kappa", Double.toString(kappa[coordid]));
                double[] means = regionMeans[coordid];
            } else {
                int outdocid = docArray.get(offset);
                System.err.println(String.format("Mismatch between "
                      + "tokens. Occurred at source document %s, "
                      + "sentence %s, token %s and target document %d, "
                      + "offset %d, token %s, token id %d",
                      currentDocumentID, currentSentenceID, word, outdocid, offset, outword, wordid));
                System.exit(1);
            }
        }
    }

    @Override
    public void setToponymAttribute(XMLStreamReader in, XMLStreamWriter out) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setCandidateAttribute(XMLStreamReader in, XMLStreamWriter out) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setCurrentDocumentID(String _string) {
        currentDocumentID = _string;
    }

    @Override
    public void setCurrentSentenceID(String _string) {
        currentSentenceID = _string;
    }
}
