/*
 * Copyright 2013 Bazaarvoice, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bazaarvoice.jolt.shiftr.spec;

import com.bazaarvoice.jolt.common.pathelement.*;
import com.bazaarvoice.jolt.exception.SpecException;
import com.bazaarvoice.jolt.common.tree.WalkedPath;
import com.bazaarvoice.jolt.utils.StringTools;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;

/**
 * A Spec Object represents a single line from the JSON Shiftr Spec.
 *
 * At a minimum a single Spec has :
 *   Raw LHS spec value
 *   Some kind of PathElement (based off that raw LHS value)
 *
 * Additionally there are 2 distinct subclasses of the base Spec
 *  LeafSpec : where the RHS is a String or Array of Strings, that specify an write path for the data from this level in the tree
 *  CompositeSpec : where the RHS is a map of children Specs
 *
 * Mapping of JSON Shiftr Spec to Spec objects :
 * {
 *   rating-*" : {      // CompositeSpec with one child and a Star PathElement
 *     "&(1)" : {       // CompositeSpec with one child and a Reference PathElement
 *       "foo: {        // CompositeSpec with one child and a Literal PathElement
 *         "value" : "Rating-&1.value"  // OutputtingSpec with a Literal PathElement and one write path
 *       }
 *     }
 *   }
 * }
 *
 * The tree structure of formed by the CompositeSpecs is what is used during Shiftr transforms
 *  to do the parallel tree walk with the input data tree.
 *
 * During the parallel tree walk a stack of data (a WalkedPath) is maintained, and used when
 *  a tree walk encounters an Outputting spec to evaluate the wildcards in the write DotNotationPath.
 */
public abstract class ShiftrSpec {

    // The processed key from the JSON config
    protected final MatchablePathElement pathElement;

    public ShiftrSpec(String rawJsonKey) {

        PathElement pe = parseSingleKeyLHS( rawJsonKey );

        if ( ! ( pe instanceof MatchablePathElement ) ) {
            throw new SpecException( "Spec LHS key=" + rawJsonKey + " is not a valid LHS key." );
        }

        this.pathElement = (MatchablePathElement) pe;
    }

    /**
     * Visible for Testing.
     *
     * Inspects the key in a particular order to determine the correct sublass of
     *  PathElement to create.
     *
     * @param origKey String that should represent a single PathElement
     * @return a concrete implementation of PathElement
     */
    public static PathElement parseSingleKeyLHS( String origKey )  {

        String elementKey;  // the String to use to actually make Elements
        String keyToInspect;  // the String to use to determine which kind of Element to create

        if ( origKey.contains( "\\" ) ) {
            // only do the extra work of processing for escaped chars, if there is one.
            keyToInspect = removeEscapedValues(origKey);
            elementKey = removeEscapeChars( origKey );
        }
        else {
            keyToInspect = origKey;
            elementKey = origKey;
        }

        //// LHS single values
        if ( "@".equals( keyToInspect ) ) {
            return new AtPathElement( elementKey );
        }
        else if ( "*".equals( keyToInspect ) ) {
            return new StarAllPathElement( elementKey );
        }
        else if ( keyToInspect.startsWith( "[" ) ) {

            if ( StringTools.countMatches(keyToInspect, "[") != 1 || StringTools.countMatches(keyToInspect, "]") != 1 ) {
                throw new SpecException( "Invalid key:" + origKey + " has too many [] references.");
            }

            return new ArrayPathElement( elementKey );
        }
        //// LHS multiple values
        else if ( keyToInspect.startsWith("@") || keyToInspect.contains( "@(" ) ) {
            // The traspose path element gets the origKey so that it has it's escapes.
            return TransposePathElement.parse( origKey );
        }
        else if ( keyToInspect.contains( "@" ) ) {
            throw new SpecException( "Invalid key:" + origKey  + " can not have an @ other than at the front." );
        }
        else if ( keyToInspect.contains("$") ) {
            return new DollarPathElement( elementKey );
        }
        else if ( keyToInspect.contains("[") ) {

            if ( StringTools.countMatches(keyToInspect, "[") != 1 || StringTools.countMatches(keyToInspect, "]") != 1 ) {
                throw new SpecException( "Invalid key:" + origKey + " has too many [] references.");
            }

            return new ArrayPathElement( elementKey );
        }
        else if ( keyToInspect.contains( "&" ) ) {

            if ( keyToInspect.contains("*") )
            {
                throw new SpecException( "Invalid key:" + origKey + ", Can't mix * with & ) ");
            }
            return new AmpPathElement( elementKey );
        }
        else if ( keyToInspect.contains("*" ) ) {

            int numOfStars = StringTools.countMatches(keyToInspect, "*");

            if(numOfStars == 1){
                return new StarSinglePathElement( elementKey );
            }
            else if(numOfStars == 2){
                return new StarDoublePathElement( elementKey );
            }
            else {
                return new StarRegexPathElement( elementKey );
            }
        }
        else if ( keyToInspect.contains("#" ) ) {
            return new HashPathElement( elementKey );
        }
        else {
            return new LiteralPathElement( elementKey );
        }
    }

    // Visible for Testing
    // given "\@pants" -> "pants"                 starts with escape
    // given "rating-\&pants" -> "rating-pants"   escape in the middle
    // given "rating\\pants" -> "ratingpants"     escape the escape char
    static String removeEscapedValues(String origKey) {
        StringBuilder sb = new StringBuilder();

        boolean prevWasEscape = false;
        for ( char c : origKey.toCharArray() ) {
            if ( '\\' == c ) {
                if ( prevWasEscape ) {
                    prevWasEscape = false;
                }
                else {
                    prevWasEscape = true;
                }
            }
            else {
                if ( ! prevWasEscape ) {
                    sb.append( c );
                }
                prevWasEscape = false;
            }
        }

        return sb.toString();
    }

    // Visible for Testing
    // given "\@pants" -> "@pants"                 starts with escape
    // given "rating-\&pants" -> "rating-&pants"   escape in the middle
    // given "rating\\pants" -> "rating\pants"     escape the escape char
    static String removeEscapeChars( String origKey ) {
        StringBuilder sb = new StringBuilder();

        boolean prevWasEscape = false;
        for ( char c : origKey.toCharArray() ) {
            if ( '\\' == c ) {
                if ( prevWasEscape ) {
                    sb.append( c );
                    prevWasEscape = false;
                }
                else {
                    prevWasEscape = true;
                }
            }
            else {
                sb.append( c );
                prevWasEscape = false;
            }
        }

        return sb.toString();
    }


    /**
     * Helper method to turn a String into an Iterator<Character>
     */
    public static Iterator<Character> stringIterator(final String string) {
        // Ensure the error is found as soon as possible.
        if (string == null)
            throw new NullPointerException();

        return new Iterator<Character>() {
            private int index = 0;

            public boolean hasNext() {
                return index < string.length();
            }

            public Character next() {

                // Throw NoSuchElementException as defined by the Iterator contract,
                // not IndexOutOfBoundsException.
                if (!hasNext())
                    throw new NoSuchElementException();
                return string.charAt(index++);
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * Given a dotNotation style outputPath like "data[2].&(1,1)", this method fixes the syntactic sugar
     * of "data[2]" --> "data.[2]"
     *
     * This makes all the rest of the String processing easier once we know that we can always
     * split on the '.' character.
     *
     * @param dotNotaton Output path dot notation
     * @return
     */
    // TODO Unit Test this
    private static String fixLeadingBracketSugar( String dotNotaton ) {

        if ( dotNotaton == null || dotNotaton.length() == 0 ) {
            return "";
        }

        char prev = dotNotaton.charAt( 0 );
        StringBuilder sb = new StringBuilder();
        sb.append( prev );

        for ( int index = 1; index < dotNotaton.length(); index++ ) {
            char curr =  dotNotaton.charAt( index );

            if ( curr == '[' && prev != '\\') {
                if ( prev == '@' || prev == '.' ) {
                    // no need to add an extra '.'
                }
                else {
                    sb.append( '.' );
                }
            }

            sb.append( curr );
            prev = curr;
        }

        return sb.toString();
    }


    /**
     * Parse RHS Transpose @ logic.
     * "@(a.b)"  --> pulls "(a.b)" off the iterator
     * "@a.b"    --> pulls just "a" off the iterator
     *
     * This method expects that the the '@' character has already been seen.
     *
     * @param iter iterator to pull data from
     * @param dotNotationRef the original dotNotation string used for error messages
     */
    // TODO Unit Test this
    private static String parseAtPathElement( Iterator<Character> iter, String dotNotationRef ) {

        if ( ! iter.hasNext() ) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // Strategy here is to walk thru the string looking for matching parenthesis.
        // '(' increments the count, while ')' decrements it
        // If we ever get negative there is a problem.
        boolean isParensAt = false;
        int atParensCount = 0;

        char c = iter.next();
        if ( c == '(' ) {
            isParensAt = true;
            atParensCount++;
        }
        else if ( c == '.' ) {
            throw new SpecException( "Unable to parse dotNotation, invalid TransposePathElement : " + dotNotationRef );
        }

        sb.append( c );

        while( iter.hasNext() ) {
            c = iter.next();
            sb.append( c );

            // Parsing "@(a.b.[&2])"
            if ( isParensAt ) {
                if ( c == '(' ) {
                    throw new SpecException( "Unable to parse dotNotation, too many open parens '(' : " + dotNotationRef );
                }
                else if ( c == ')' ) {
                    atParensCount--;
                }

                if ( atParensCount == 0 ) {
                    return sb.toString();
                }
                else if ( atParensCount < 0 ) {
                    throw new SpecException( "Unable to parse dotNotation, specifically the '@()' part : " + dotNotationRef );
                }
            }
            // Parsing "@abc.def, return a canonical form of "@(abc)" and leave the "def" in the iterator
            else if ( c == '.' ) {
                return "(" + sb.toString().substring( 0, sb.length() - 1 ) + ")";
            }
        }

        // if we got to the end of the String and we have mismatched parenthesis throw an exception.
        if ( isParensAt && atParensCount != 0 ) {
            throw new SpecException( "Invalid @() pathElement from : " + dotNotationRef );
        }
        // Parsing "@abc"
        return sb.toString();
    }

    /**
     * Method that recursively parses a dotNotation String based on an iterator.
     *
     * This method will call out to parseAtPathElement
     *
     * @param pathStrings List to store parsed Strings that each represent a PathElement
     * @param iter the iterator to pull characters from
     * @param dotNotationRef the original dotNotation string used for error messages
     * @return
     */
    public static List<String> parseDotNotation( List<String> pathStrings, Iterator<Character> iter,
                                                 String dotNotationRef ) {

        if ( ! iter.hasNext() ) {
            return pathStrings;
        }

        // Leave the forward slashes, unless it precedes a "."
        // The way this works is always supress the forward slashes, but add them back in if the next char is not a "."

        boolean prevIsEscape = false;
        boolean currIsEscape = false;
        StringBuilder sb = new StringBuilder();

        char c;
        while( iter.hasNext() ) {

            c = iter.next();

            currIsEscape = false;
            if ( c == '\\' && ! prevIsEscape ) {
                // current is Escape only if the char is escape, or
                //  it is an Escape and the prior char was, then don't consider this one an escape
                currIsEscape = true;
            }

            if ( prevIsEscape && c != '.' && c != '\\') {
                sb.append( '\\' );
                sb.append( c );
            }
            else if( c == '@' ) {
                sb.append( '@' );
                sb.append( parseAtPathElement( iter, dotNotationRef ) );

                //                      there was a "[" seen       but no "]"
                boolean isPartOfArray = sb.indexOf( "[" ) != -1 && sb.indexOf( "]" ) == -1;
                if ( ! isPartOfArray ) {
                    pathStrings.add( sb.toString() );
                    sb = new StringBuilder();
                }
            }
            else if ( c == '.' ) {

                if ( prevIsEscape ) {
                    sb.append( '.' );
                }
                else {
                    if ( sb.length() != 0 ) {
                        pathStrings.add( sb.toString() );
                    }
                    return parseDotNotation( pathStrings, iter, dotNotationRef );
                }
            }
            else if ( ! currIsEscape ) {
                sb.append( c );
            }

            prevIsEscape = currIsEscape;
        }

        if ( sb.length() != 0 ) {
            pathStrings.add( sb.toString() );
        }
        return pathStrings;
    }

    /**
     * @param refDotNotation the original dotNotation string used for error messages
     * @return List of PathElements based on the provided List<String> keys
     */
    private static List<PathElement> parseList( List<String> keys, String refDotNotation ) {
        ArrayList<PathElement> paths = new ArrayList<>();

        for( String key: keys ) {
            PathElement path = parseSingleKeyLHS( key );
            if ( path instanceof AtPathElement ) {
                throw new SpecException( "'.@.' is not valid on the RHS: " + refDotNotation );
            }
            paths.add( path );
        }

        return paths;
    }

    /**
     * Parse the dotNotation of the RHS.
     */
    public static List<PathElement> parseDotNotationRHS( String dotNotation ) {
        String fixedNotation = fixLeadingBracketSugar( dotNotation );
        List<String> pathStrs = parseDotNotation( new LinkedList<String>(), stringIterator( fixedNotation ), dotNotation );

        return parseList( pathStrs, dotNotation );
    }


    /**
     * This is the main recursive method of the Shiftr parallel "spec" and "input" tree walk.
     *
     * It should return true if this Spec object was able to successfully apply itself given the
     *  inputKey and input object.
     *
     * In the context of the Shiftr parallel treewalk, if this method returns true, the assumption
     *  is that no other sibling Shiftr specs need to look at this particular input key.
     *
     * @return true if this this spec "handles" the inputkey such that no sibling specs need to see it
     */
    public abstract boolean apply( String inputKey, Object input, WalkedPath walkedPath, Map<String,Object> output );
}
