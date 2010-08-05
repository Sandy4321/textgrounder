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
package opennlp.rlda.wrapper.io;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import opennlp.rlda.apps.ConverterExperimentParameters;
import opennlp.rlda.textstructs.Lexicon;
import opennlp.rlda.topostructs.Region;

/**
 *
 * @author Taesun Moon <tsunmoon@gmail.com>
 */
public abstract class InputReader extends IOBase {

    public InputReader(ConverterExperimentParameters _experimentParameters) {
        super(_experimentParameters);
        tokenArrayFile = new File(experimentParameters.getTokenArrayOutputPath());
    }

    public void readLexicon(Lexicon _lexicon) {
        ObjectInputStream lexiconIn = null;
        try {
            lexiconIn = new ObjectInputStream(new GZIPInputStream(new FileInputStream(lexiconFile.getCanonicalPath())));
            _lexicon = (Lexicon) lexiconIn.readObject();
        } catch (IOException ex) {
            Logger.getLogger(InputReader.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(InputReader.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }
    }

    public void readRegions(Region[][] _regions) {
        ObjectInputStream regionIn = null;
        try {
            regionIn = new ObjectInputStream(new GZIPInputStream(new FileInputStream(regionFile.getCanonicalPath())));
            _regions = (Region[][]) regionIn.readObject();
        } catch (IOException ex) {
            Logger.getLogger(InputReader.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(InputReader.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }
    }

    public abstract void openTokenArrayReader();

    public abstract int[] nextTokenArrayRecord() throws EOFException,
          IOException;
}
