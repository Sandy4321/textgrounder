///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Ben Wing, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////
package opennlp.textgrounder.textstructs;

import gnu.trove.TIntIterator;

import java.util.List;

import org.jdom.Attribute;
import org.jdom.Element;

import opennlp.textgrounder.topostructs.*;
import opennlp.textgrounder.gazetteers.*;

/**
 * Class that stores data about a single token in a sequence of tokens. Tokens
 * are generally children of Divisions.
 * 
 * NOTE: A single token may correspond to multiple words, particularly in the
 * case of multi-word place names.
 * 
 * @author benwing
 */
public class Token extends DocumentComponent {
    static private final long serialVersionUID = 1L;

    public int id; // Identifier in a lexicon
    public boolean istop;
    public Location goldLocation; // Gold-standard location, if known 
    
    public Token(CorpusDocument doc, boolean istop) {
        super(doc, "SHOULD_NOT_BE_SEEN");
        this.istop = istop;
    }
    
    public Token(CorpusDocument doc, int id, boolean istop) {
        this(doc, istop);
        this.id = id;
    }
    
    protected void copyElementProperties(Element e) {
        istop = e.getName().equals("toponym");
        for (Attribute att : (List<Attribute>) e.getAttributes()) {
            String name = att.getName();
            String value = att.getValue();
            // System.out.println("name=" + name + ", value=" + value);
            if ((!istop && name.equals("tok")) || (istop && name.equals("term")))
                id = document.corpus.lexicon.addWord(value);
            else
                props.put(name, value);
        }
        assert (id != 0);
    }

    /**
     * Create a new XML Element corresponding to the current component
     * (including its children).
     */
    protected Element outputElement() {
        Element e = new Element(istop ? "toponym" : "w");
        String word = document.corpus.lexicon.getWordForInt(id);
        if (istop) {
            e.setAttribute("term", word);
            Element cands = new Element("candidates");
            e.addContent(cands);
            Gazetteer gazetteer = document.corpus.gazetteer;
            /* This totally sucks.  Why can't I iterate in the obvious way? */
            // System.out.println("Toponym is " + word);
            for (TIntIterator it = gazetteer.get(word).iterator(); it.hasNext();) {
                int locid = it.next();
                Location loc = gazetteer.getLocation(locid);
                Element cand = new Element("cand");
                cands.addContent(cand);
                cand.setAttribute("id", "c" + locid);
                Coordinate coord = loc.getCoord();
                /* Java sucks.  Why can't I just call toString() on a double? */
                cand.setAttribute("lat", "" + coord.latitude);
                cand.setAttribute("long", "" + coord.longitude);
                cand.setAttribute("name", loc.getName());
                if (loc.getType() != null)
                    cand.setAttribute("type", loc.getType());
                if (loc.getContainer() != null)
                    cand.setAttribute("container", loc.getContainer());
                if (loc.getPop() > 0)
                    cand.setAttribute("population", "" + loc.getPop());
            }
        } else {
            assert (word != null);
            e.setAttribute("tok", word);
        }
        // copy properties
        for (String name : props.keySet())
            e.setAttribute(name, props.get(name));
        // there should be no children
        for (DocumentComponent child : this)
            assert (false);
        return e;
    }
}
