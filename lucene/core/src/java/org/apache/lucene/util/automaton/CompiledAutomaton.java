package org.apache.lucene.util.automaton;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
  
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.SingleTermsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;

/**
 * Immutable class holding compiled details for a given
 * Automaton.  The Automaton is deterministic, must not have
 * dead states but is not necessarily minimal.
 *
 * @lucene.experimental
 */
public class CompiledAutomaton {
  /**
   * Automata are compiled into different internal forms for the
   * most efficient execution depending upon the language they accept.
   */
  public enum AUTOMATON_TYPE {
    /** Automaton that accepts no strings. */
    NONE, 
    /** Automaton that accepts all possible strings. */
    ALL, 
    /** Automaton that accepts only a single fixed string. */
    SINGLE, 
    /** Automaton that matches all Strings with a constant prefix. */
    PREFIX, 
    /** Automaton that matches all binary terms (BytesRef) in a range from minTerm (inclusive or not) to maxTerm (inclusive or not). */
    RANGE, 
    /** Catch-all for any other automata. */
    NORMAL
  };

  /** If simplify is true this will be the "simplified" type; else, this is NORMAL */
  public final AUTOMATON_TYPE type;

  /** 
   * For {@link AUTOMATON_TYPE#PREFIX}, this is the prefix term; 
   * for {@link AUTOMATON_TYPE#SINGLE} this is the singleton term.
   * for {@link AUTOMATON_TYPE#RANGE}, this is the min term; 
   */
  public final BytesRef term;

  /** 
   * Only used for {@link AUTOMATON_TYPE#RANGE}; 
   */
  public final BytesRef maxTerm;

  /** 
   * Only used for {@link AUTOMATON_TYPE#RANGE}: true if the min term is included in the range. 
   */
  public final boolean minInclusive;

  /** 
   * Only used for {@link AUTOMATON_TYPE#RANGE}: true if the max term is included in the range. 
   */
  public final boolean maxInclusive;

  /** 
   * Matcher for quickly determining if a byte[] is accepted.
   * only valid for {@link AUTOMATON_TYPE#NORMAL}.
   */
  public final ByteRunAutomaton runAutomaton;

  /**
   * Two dimensional array of transitions, indexed by state
   * number for traversal. The state numbering is consistent with
   * {@link #runAutomaton}. 
   * Only valid for {@link AUTOMATON_TYPE#NORMAL}.
   */
  public final Automaton automaton;

  /**
   * Shared common suffix accepted by the automaton. Only valid
   * for {@link AUTOMATON_TYPE#NORMAL}, and only when the
   * automaton accepts an infinite language.
   */
  public final BytesRef commonSuffixRef;

  /**
   * Indicates if the automaton accepts a finite set of strings.
   * Null if this was not computed.
   * Only valid for {@link AUTOMATON_TYPE#NORMAL}.
   */
  public final Boolean finite;

  /** Which state accepts all suffixes; only set for RANGE and PREFIX, else -1. */
  public final int sinkState;

  /** Create this, passing simplify=true and finite=null, so that we try
   *  to simplify the automaton and determine if it is finite. */
  public CompiledAutomaton(Automaton automaton) {
    this(automaton, null, true);
  }

  // TODO: we could also allow direct binary automaton here: BlockTree can optimize that case more generally too, but we start with this
  // more restricted (single term range) API:

  /** Matches a range of terms.  Some terms dict implementations (e.g. BlockTree) can optimize this case by using
   *  pre-computed auto prefix terms stored in the index. */
  public CompiledAutomaton(BytesRef minTerm, boolean minInclusive, BytesRef maxTerm, boolean maxInclusive) {
    if (minTerm == null) {
      this.term = new BytesRef();
      this.minInclusive = true;
    } else {
      this.term = minTerm;
      this.minInclusive = minInclusive;
    }
    this.maxTerm = maxTerm;
    this.maxInclusive = maxInclusive;
    commonSuffixRef = null;
    finite = false;
    automaton = Automata.makeBinaryInterval(term, minInclusive, maxTerm, maxInclusive);

    // Compute sinkState for this automaton, if any (it won't exist if maxTerm == minTerm):
    int numStates = automaton.getNumStates();
    Transition t = new Transition();
    int foundState = -1;
    for(int s=0;s<numStates;s++) {
      if (automaton.isAccept(s)) {
        int count = automaton.initTransition(s, t);
        boolean isSinkState = false;
        for(int i=0;i<count;i++) {
          automaton.getNextTransition(t);
          if (t.dest == s && t.min == 0 && t.max == 0xff) {
            isSinkState = true;
            break;
          }
        }
        if (isSinkState) {
          foundState = s;
          break;
        }
      }
    }
    sinkState = foundState;
    // It's safe to allow unlimited determinized here:
    runAutomaton = new ByteRunAutomaton(automaton, true, Integer.MAX_VALUE);
    type = AUTOMATON_TYPE.RANGE;
  }

  /** Matches the specified prefix term.  Some terms dict implementations (e.g. BlockTree) can optimize this case by using
   *  pre-computed auto prefix terms stored in the index. */
  public CompiledAutomaton(BytesRef prefixTerm) {
    this.term = prefixTerm;
    type = AUTOMATON_TYPE.PREFIX;
    automaton = new Automaton();
    int lastState = automaton.createState();
    for(int i=0;i<prefixTerm.length;i++) {
      int state = automaton.createState();
      automaton.addTransition(lastState, state, prefixTerm.bytes[prefixTerm.offset+i]&0xff);
      lastState = state;
    }
    automaton.setAccept(lastState, true);
    automaton.addTransition(lastState, lastState, 0, 255);
    sinkState = lastState;
    automaton.finishState();
    // It's safe to allow unlimited determinized here:
    runAutomaton = new ByteRunAutomaton(automaton, true, Integer.MAX_VALUE);
    commonSuffixRef = null;
    finite = false;
    minInclusive = false;
    maxInclusive = false;
    maxTerm = null;
  }

  /** Create this.  If finite is null, we use {@link Operations#isFinite}
   *  to determine whether it is finite.  If simplify is true, we run
   *  possibly expensive operations to determine if the automaton is one
   *  the cases in {@link CompiledAutomaton.AUTOMATON_TYPE}. */
  public CompiledAutomaton(Automaton automaton, Boolean finite, boolean simplify) {
    this(automaton, finite, simplify, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
  }


  /** Create this.  If finite is null, we use {@link Operations#isFinite}
   *  to determine whether it is finite.  If simplify is true, we run
   *  possibly expensive operations to determine if the automaton is one
   *  the cases in {@link CompiledAutomaton.AUTOMATON_TYPE}. If simplify
   *  requires determinizing the autaomaton then only maxDeterminizedStates
   *  will be created.  Any more than that will cause a
   *  TooComplexToDeterminizeException.
   */
  public CompiledAutomaton(Automaton automaton, Boolean finite, boolean simplify,
      int maxDeterminizedStates) {
    if (automaton.getNumStates() == 0) {
      automaton = new Automaton();
      automaton.createState();
    }

    if (simplify) {

      // Test whether the automaton is a "simple" form and
      // if so, don't create a runAutomaton.  Note that on a
      // large automaton these tests could be costly:

      if (Operations.isEmpty(automaton)) {
        // matches nothing
        type = AUTOMATON_TYPE.NONE;
        term = null;
        commonSuffixRef = null;
        runAutomaton = null;
        this.automaton = null;
        this.finite = null;
        maxTerm = null;
        minInclusive = false;
        maxInclusive = false;
        sinkState = -1;
        return;
      // NOTE: only approximate, because automaton may not be minimal:
      } else if (Operations.isTotal(automaton)) {
        // matches all possible strings
        type = AUTOMATON_TYPE.ALL;
        term = null;
        commonSuffixRef = null;
        runAutomaton = null;
        this.automaton = null;
        this.finite = null;
        maxTerm = null;
        minInclusive = false;
        maxInclusive = false;
        sinkState = -1;
        return;
      } else {

        automaton = Operations.determinize(automaton, maxDeterminizedStates);

        final String commonPrefix = Operations.getCommonPrefix(automaton);
        final String singleton;

        if (commonPrefix.length() > 0 && Operations.sameLanguage(automaton, Automata.makeString(commonPrefix))) {
          singleton = commonPrefix;
        } else {
          singleton = null;
        }

        if (singleton != null) {
          // matches a fixed string
          type = AUTOMATON_TYPE.SINGLE;
          term = new BytesRef(singleton);
          commonSuffixRef = null;
          runAutomaton = null;
          this.automaton = null;
          this.finite = null;
          maxTerm = null;
          minInclusive = false;
          maxInclusive = false;
          sinkState = -1;
          return;
        } else if (commonPrefix.length() > 0) {
          Automaton other = Operations.concatenate(Automata.makeString(commonPrefix), Automata.makeAnyString());
          other = Operations.determinize(other, maxDeterminizedStates);
          assert Operations.hasDeadStates(other) == false;
          if (Operations.sameLanguage(automaton, other)) {
            // matches a constant prefix
            type = AUTOMATON_TYPE.PREFIX;
            term = new BytesRef(commonPrefix);
            commonSuffixRef = null;
            automaton = new Automaton();
            int lastState = automaton.createState();
            for(int i=0;i<term.length;i++) {
              int state = automaton.createState();
              automaton.addTransition(lastState, state, term.bytes[term.offset+i]&0xff);
              lastState = state;
            }
            automaton.setAccept(lastState, true);
            automaton.addTransition(lastState, lastState, 0, 255);
            sinkState = lastState;
            automaton.finishState();
            this.automaton = automaton;
            // It's safe to allow unlimited determinized here:
            runAutomaton = new ByteRunAutomaton(automaton, true, Integer.MAX_VALUE);
            this.finite = false;
            maxTerm = null;
            minInclusive = false;
            maxInclusive = false;
            return;
          }
        }
      }
    }

    sinkState = -1;

    type = AUTOMATON_TYPE.NORMAL;
    term = null;
    maxTerm = null;
    minInclusive = false;
    maxInclusive = false;

    if (finite == null) {
      this.finite = Operations.isFinite(automaton);
    } else {
      this.finite = finite;
    }

    Automaton binary = new UTF32ToUTF8().convert(automaton);
    if (this.finite) {
      commonSuffixRef = null;
    } else {
      // NOTE: this is a very costly operation!  We should test if it's really warranted in practice...
      commonSuffixRef = Operations.getCommonSuffixBytesRef(binary, maxDeterminizedStates);
    }
    runAutomaton = new ByteRunAutomaton(binary, true, maxDeterminizedStates);

    this.automaton = runAutomaton.automaton;
  }

  private Transition transition = new Transition();
  
  //private static final boolean DEBUG = BlockTreeTermsWriter.DEBUG;

  private BytesRef addTail(int state, BytesRefBuilder term, int idx, int leadLabel) {
    //System.out.println("addTail state=" + state + " term=" + term.utf8ToString() + " idx=" + idx + " leadLabel=" + (char) leadLabel);
    //System.out.println(automaton.toDot());
    // Find biggest transition that's < label
    // TODO: use binary search here
    int maxIndex = -1;
    int numTransitions = automaton.initTransition(state, transition);
    for(int i=0;i<numTransitions;i++) {
      automaton.getNextTransition(transition);
      if (transition.min < leadLabel) {
        maxIndex = i;
      } else {
        // Transitions are alway sorted
        break;
      }
    }

    //System.out.println("  maxIndex=" + maxIndex);

    assert maxIndex != -1;
    automaton.getTransition(state, maxIndex, transition);

    // Append floorLabel
    final int floorLabel;
    if (transition.max > leadLabel-1) {
      floorLabel = leadLabel-1;
    } else {
      floorLabel = transition.max;
    }
    //System.out.println("  floorLabel=" + (char) floorLabel);
    term.grow(1+idx);
    //if (DEBUG) System.out.println("  add floorLabel=" + (char) floorLabel + " idx=" + idx);
    term.setByteAt(idx, (byte) floorLabel);

    state = transition.dest;
    //System.out.println("  dest: " + state);
    idx++;

    // Push down to last accept state
    while (true) {
      numTransitions = automaton.getNumTransitions(state);
      if (numTransitions == 0) {
        //System.out.println("state=" + state + " 0 trans");
        assert runAutomaton.isAccept(state);
        term.setLength(idx);
        //if (DEBUG) System.out.println("  return " + term.utf8ToString());
        return term.get();
      } else {
        // We are pushing "top" -- so get last label of
        // last transition:
        //System.out.println("get state=" + state + " numTrans=" + numTransitions);
        automaton.getTransition(state, numTransitions-1, transition);
        term.grow(1+idx);
        //if (DEBUG) System.out.println("  push maxLabel=" + (char) lastTransition.max + " idx=" + idx);
        //System.out.println("  add trans dest=" + scratch.dest + " label=" + (char) scratch.max);
        term.setByteAt(idx, (byte) transition.max);
        state = transition.dest;
        idx++;
      }
    }
  }

  // TODO: should this take startTerm too?  This way
  // Terms.intersect could forward to this method if type !=
  // NORMAL:
  /** Return a {@link TermsEnum} intersecting the provided {@link Terms}
   *  with the terms accepted by this automaton. */
  public TermsEnum getTermsEnum(Terms terms) throws IOException {
    switch(type) {
    case NONE:
      return TermsEnum.EMPTY;
    case ALL:
      return terms.iterator(null);
    case SINGLE:
      return new SingleTermsEnum(terms.iterator(null), term);
    case PREFIX:
    case NORMAL:
    case RANGE:
      return terms.intersect(this, null);
    default:
      // unreachable
      throw new RuntimeException("unhandled case");
    }
  }

  /** Finds largest term accepted by this Automaton, that's
   *  &lt;= the provided input term.  The result is placed in
   *  output; it's fine for output and input to point to
   *  the same bytes.  The returned result is either the
   *  provided output, or null if there is no floor term
   *  (ie, the provided input term is before the first term
   *  accepted by this Automaton). */
  public BytesRef floor(BytesRef input, BytesRefBuilder output) {

    //if (DEBUG) System.out.println("CA.floor input=" + input.utf8ToString());

    int state = runAutomaton.getInitialState();

    // Special case empty string:
    if (input.length == 0) {
      if (runAutomaton.isAccept(state)) {
        output.clear();
        return output.get();
      } else {
        return null;
      }
    }

    final List<Integer> stack = new ArrayList<>();

    int idx = 0;
    while (true) {
      int label = input.bytes[input.offset + idx] & 0xff;
      int nextState = runAutomaton.step(state, label);
      //if (DEBUG) System.out.println("  cycle label=" + (char) label + " nextState=" + nextState);

      if (idx == input.length-1) {
        if (nextState != -1 && runAutomaton.isAccept(nextState)) {
          // Input string is accepted
          output.grow(1+idx);
          output.setByteAt(idx, (byte) label);
          output.setLength(input.length);
          //if (DEBUG) System.out.println("  input is accepted; return term=" + output.utf8ToString());
          return output.get();
        } else {
          nextState = -1;
        }
      }

      if (nextState == -1) {

        // Pop back to a state that has a transition
        // <= our label:
        while (true) {
          int numTransitions = automaton.getNumTransitions(state);
          if (numTransitions == 0) {
            assert runAutomaton.isAccept(state);
            output.setLength(idx);
            //if (DEBUG) System.out.println("  return " + output.utf8ToString());
            return output.get();
          } else {
            automaton.getTransition(state, 0, transition);

            if (label-1 < transition.min) {

              if (runAutomaton.isAccept(state)) {
                output.setLength(idx);
                //if (DEBUG) System.out.println("  return " + output.utf8ToString());
                return output.get();
              }
              // pop
              if (stack.size() == 0) {
                //if (DEBUG) System.out.println("  pop ord=" + idx + " return null");
                return null;
              } else {
                state = stack.remove(stack.size()-1);
                idx--;
                //if (DEBUG) System.out.println("  pop ord=" + (idx+1) + " label=" + (char) label + " first trans.min=" + (char) transitions[0].min);
                label = input.bytes[input.offset + idx] & 0xff;
              }
            } else {
              //if (DEBUG) System.out.println("  stop pop ord=" + idx + " first trans.min=" + (char) transitions[0].min);
              break;
            }
          }
        }

        //if (DEBUG) System.out.println("  label=" + (char) label + " idx=" + idx);

        return addTail(state, output, idx, label);
        
      } else {
        output.grow(1+idx);
        output.setByteAt(idx, (byte) label);
        stack.add(state);
        state = nextState;
        idx++;
      }
    }
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((runAutomaton == null) ? 0 : runAutomaton.hashCode());
    result = prime * result + ((term == null) ? 0 : term.hashCode());
    result = prime * result + ((type == null) ? 0 : type.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    CompiledAutomaton other = (CompiledAutomaton) obj;
    if (type != other.type) return false;
    if (type == AUTOMATON_TYPE.SINGLE || type == AUTOMATON_TYPE.PREFIX) {
      if (!term.equals(other.term)) return false;
    } else if (type == AUTOMATON_TYPE.NORMAL) {
      if (!runAutomaton.equals(other.runAutomaton)) return false;
    }

    return true;
  }
}
