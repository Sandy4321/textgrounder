///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Travis Brown, The University of Texas at Austin
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
package opennlp.textgrounder.text;

public class Token {
  private final int idx;
  private final String form;

  Token(String form) {
    this(-1, form);
  }

  Token(int idx, String form) {
    this.idx = idx;
    this.form = form;
  }

  public int getIdx() {
    if (this.idx == -1) {
      throw new UnsupportedOperationException("This token has no index.");
    } else {
      return this.idx;
    }
  }

  public String getForm() {
    return this.form;
  }

  public boolean isToponym() {
      return false;
  }
}

