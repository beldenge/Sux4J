package it.unimi.dsi.sux4j.mph;

/*		 
 * Sux4J: Succinct data structures for Java
 *
 * Copyright (C) 2008 Sebastiano Vigna 
 *
 *  This library is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as published by the Free
 *  Software Foundation; either version 2.1 of the License, or (at your option)
 *  any later version.
 *
 *  This library is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 */

import it.unimi.dsi.Util;
import it.unimi.dsi.bits.BitVector;
import it.unimi.dsi.bits.LongArrayBitVector;
import it.unimi.dsi.bits.TransformationStrategies;
import it.unimi.dsi.bits.TransformationStrategy;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongFunction;
import it.unimi.dsi.fastutil.objects.AbstractObjectIterator;
import it.unimi.dsi.fastutil.objects.Object2LongFunction;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.io.InputBitStream;
import it.unimi.dsi.io.OutputBitStream;
import it.unimi.dsi.lang.MutableString;
import it.unimi.dsi.sux4j.bits.Rank9;
import it.unimi.dsi.sux4j.bits.SimpleSelect;
import it.unimi.dsi.sux4j.util.EliasFanoLongBigList;
import it.unimi.dsi.util.LongBigList;
import static it.unimi.dsi.sux4j.mph.HypergraphSorter.GAMMA;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.log4j.Logger;

/** A distributor based on a hollow trie.
 * 
 * <h2>Implementation details</h2>
 * 
 * <p>This class implements a distributor on top of a hollow trie. First, a compacted trie is built from the delimiter set.
 * Then, for each key we compute the node of the trie in which the bucket of the key is established. This gives us,
 * for each internal and external node of the trie, a set of paths to which we must associate an action (exit on the left,
 * go through, exit on the right). Overall, the number of such paths is equal to the number of keys plus the number of delimiters, so
 * the mapping from each pair node/path to the respective action takes linear space. Now, from the compacted trie we just
 * retain a hollow trie, as the path-length information is sufficient to rebuild the keys of the above mapping. 
 * By sizing the bucket size around the logarithm of the average length, we obtain a distributor that occupies linear space.
 */

public class HollowTrieDistributor<T> extends AbstractObject2LongFunction<T> {
	private final static Logger LOGGER = Util.getLogger( HollowTrieDistributor.class );
	private static final long serialVersionUID = 1L;
	private static final boolean DEBUG = false;
	private static final boolean DDEBUG = false;
	private static final boolean ASSERTS = false;

	/** An integer representing the exit-on-the-left behaviour. */
	private final static int LEFT = 0;
	/** An integer representing the exit-on-the-right behaviour. */
	private final static int RIGHT = 1;
	/** An integer representing the follow-the-try behaviour. */
	private final static int FOLLOW = 2;
	
	/** The transformation used to map object to bit vectors. */
	private final TransformationStrategy<? super T> transformationStrategy;
	/** The bitstream representing the hollow trie. */
	private final BitVector trie;
	/** A ranking structure over {@link #trie}. */
	private final Rank9 rank9;
	/** A selection structure over {@link #trie}. */
	private final SimpleSelect select;
	/** The list of skips, indexed by the internal nodes (we do not need skips on the leaves). */
	private final EliasFanoLongBigList skips;
	/** For each internal node and each possible path, the related behaviour. */
	private final MWHCFunction<BitVector> internalBehaviour;
	/** For each external node and each possible path, the related behaviour. */
	private final MWHCFunction<BitVector> externalBehaviour;
	/** The number of (internal and external) nodes of the trie. */
	private final int size;
	/** A debug function used to store explicitly {@link #internalBehaviour}. */
	private final Object2LongFunction<BitVector> internalTestFunction;
	/** A debug function used to store explicitly {@link #externalBehaviour}. */
	private final Object2LongFunction<BitVector> externalTestFunction;
	
	/** An intermediate class containing the compacted trie generated by the delimiters. After its construction,
	 * {@link #internalKeysFile} and {@link #externalKeysFile} contain the pairs node/path that must be mapped
	 * to {@link #internalValues} and {@link #lValues}, respectively, to obtain the desired behaviour. */
	private final static class IntermediateTrie<T> {
		/** A debug function used to store explicitly the internal behaviour. */
		private Object2LongFunction<BitVector> internalTestFunction;
		/** A debug function used to store explicitly the internal behaviour. */
		private Object2LongFunction<BitVector> externalTestFunction;
		/** The root of the trie. */
		protected final Node root;
		/** The number of overall elements to distribute. */
		private final int numElements;
		/** The number of internal nodes of the trie. */
		protected final int size;
		/** The file containing the internal keys (pairs node/path). */
		private final File internalKeysFile;
		/** The file containing the external keys (pairs node/path). */
		private final File externalKeysFile;
		/** The values associated to the keys in {@link #internalKeysFile}. */
		private LongBigList internalValues;
		/** The values associated to the keys in {@link #externalKeysFile}. */
		private LongBigList externalValues;
		
		/** A node in the trie. */
		private static class Node {
			/** Left child. */
			private Node left;
			/** Right child. */
			private Node right;
			/** The path compacted in this node (<code>null</code> if there is no compaction at this node). */
			private final LongArrayBitVector path;
			/** Whether we have already emitted the path at this node during the computation of the behaviour. */
			private boolean emitted;
			/** The index of this node in breadth-first order. */
			private int index;
			
			/** Creates a node. 
			 * 
			 * @param left the left child.
			 * @param right the right child.
			 * @param path the path compacted at this node.
			 */
			public Node( final Node left, final Node right, final LongArrayBitVector path ) {
				this.left = left;
				this.right = right;
				this.path = path;
			}

			/** Returns true if this node is a leaf.
			 * 
			 * @return true if this node is a leaf.
			 */
			public boolean isLeaf() {
				return right == null && left == null;
			}
			
			public String toString() {
				return "[" + path + "]";
			}
		}
			
		
		/** Creates a partial compacted trie using given elements, bucket size and transformation strategy.
		 * 
		 * @param elements the elements among which the trie must be able to rank.
		 * @param bucketSize the size of a bucket.
		 * @param transformationStrategy a transformation strategy that must turn the elements in <code>elements</code> into a list of
		 * distinct, prefix-free, lexicographically increasing (in iteration order) bit vectors.
		 * @param tempDir a directory for the temporary files created during construction, or <code>null</code> for the default temporary directory. 
		 */
		
		public IntermediateTrie( final Iterable<? extends T> elements, final int bucketSize, final TransformationStrategy<? super T> transformationStrategy, final File tempDir ) throws IOException {
			if ( ASSERTS ) {
				internalTestFunction = new Object2LongOpenHashMap<BitVector>();
				externalTestFunction = new Object2LongOpenHashMap<BitVector>();
				internalTestFunction.defaultReturnValue( -1 );
				externalTestFunction.defaultReturnValue( -1 );
			}
			
			Iterator<? extends T> iterator = elements.iterator(); 

			if ( iterator.hasNext() ) {
				LongArrayBitVector prev = LongArrayBitVector.copy( transformationStrategy.toBitVector( iterator.next() ) );
				LongArrayBitVector prevDelimiter = LongArrayBitVector.getInstance();
				
				Node node, root = null;
				BitVector curr;
				int cmp, pos, prefix, count = 1;
				long maxLength = prev.length();
				
				while( iterator.hasNext() ) {
					// Check order
					curr = transformationStrategy.toBitVector( iterator.next() ).fast();
					cmp = prev.compareTo( curr );
					if ( cmp == 0 ) throw new IllegalArgumentException( "The input bit vectors are not distinct" );
					if ( cmp > 0 ) throw new IllegalArgumentException( "The input bit vectors are not lexicographically sorted" );
					if ( curr.longestCommonPrefixLength( prev ) == prev.length() ) throw new IllegalArgumentException( "The input bit vectors are not prefix-free" );

					if ( count % bucketSize == 0 ) {
						// Found delimiter. Insert into trie.
						if ( root == null ) {
							root = new Node( null, null, prev.copy() );
							prevDelimiter.replace( prev );
						}
						else {
							prefix = (int)prev.longestCommonPrefixLength( prevDelimiter );

							pos = 0;
							node = root;
							Node n = null;
							while( node != null ) {
								final long pathLength = node.path.length();
								if ( prefix < pathLength ) {
									n = new Node( node.left, node.right, node.path.copy( prefix + 1, pathLength ) );
									node.path.length( prefix );
									node.path.trim();
									node.left = n;
									node.right = new Node( null, null, prev.copy( pos + prefix + 1, prev.length() ) ); 
									break;
								}

								prefix -= pathLength + 1;
								pos += pathLength + 1;
								node = node.right;
								if ( ASSERTS ) assert node == null || prefix >= 0 : prefix + " <= " + 0;
							}

							if ( ASSERTS ) assert node != null;

							prevDelimiter.replace( prev );
						}
					}
					prev.replace( curr );
					maxLength = Math.max( maxLength, prev.length() );
					count++;
				}

				this.numElements = count;
				this.root = root;
				
				internalKeysFile = File.createTempFile( HollowTrieDistributor.class.getName(), "int", tempDir );
				externalKeysFile = File.createTempFile( HollowTrieDistributor.class.getName(), "ext", tempDir );
				internalKeysFile.deleteOnExit();
				externalKeysFile.deleteOnExit();

				if ( root != null ) {
					LOGGER.info( "Numbering nodes..." );

					ObjectArrayList<Node> queue = new ObjectArrayList<Node>();
					int p = 0;

					// TODO: this is ugly
					queue.add( root );
					while( p < queue.size() ) {
						node = queue.get( p );
						node.index = p;
						if ( node.left != null ) queue.add( node.left );
						if ( node.right != null ) queue.add( node.right );
						p++;
					}
					queue = null;
					size = p;

					LOGGER.info( "Computing function keys..." );

					final OutputBitStream internalKeys = new OutputBitStream( internalKeysFile );
					final OutputBitStream externalKeys = new OutputBitStream( externalKeysFile );

					internalValues = LongArrayBitVector.getInstance().asLongBigList( 2 );
					externalValues = LongArrayBitVector.getInstance().asLongBigList( 1 );
					iterator = elements.iterator();

					// The stack of nodes visited the last time
					final Node stack[] = new Node[ (int)maxLength ];
					// The length of the path compacted in the trie up to the corresponding node, excluded
					final int[] len = new int[ (int)maxLength ];
					stack[ 0 ] = root;
					int depth = 0, behaviour, pathLength;
					boolean first = true;
					Node lastNode = null;
					BitVector currFromPos, path, lastPath = null;
					LongArrayBitVector nodePath;
					OutputBitStream obs;

					while( iterator.hasNext() ) {
						curr = transformationStrategy.toBitVector( iterator.next() ).fast();
						if ( DEBUG ) System.err.println( curr );
						if ( ! first )  {
							// Adjust stack using lcp between present string and previous one
							prefix = (int)prev.longestCommonPrefixLength( curr );
							while( depth > 0 && len[ depth ] > prefix ) depth--;
						}
						else first = false;
						node = stack[ depth ];
						pos = len[ depth ];

						for(;;) {
							nodePath = node.path;
							currFromPos = curr.subVector( pos ); 
							prefix = (int)currFromPos.longestCommonPrefixLength( nodePath );

							if ( prefix < nodePath.length() || ! node.emitted ) {
								// Either we must code an exit behaviour, or the follow behaviour of this node has not been coded yet.
								if ( prefix == nodePath.length() ) {
									// Follow. Behaviour is LEFT on external nodes and FOLLOW on internal nodes. The path is the node path. 
									node.emitted = true;
									behaviour = node.isLeaf() ? LEFT : FOLLOW;
									path = nodePath;

									if ( ASSERTS ) assert ! node.isLeaf() || currFromPos.length() == nodePath.length();
								}
								else {
									// Exit. LEFT or RIGHT, depending on the bit at the end of the common prefix. The
									// path is the remaining path at the current position for external nodes, or a prefix of length
									// at most pathLength for internal nodes.
									behaviour = nodePath.getBoolean( prefix ) ? LEFT : RIGHT;
									path = node.isLeaf() ? currFromPos.copy() :	currFromPos.subVector( 0, Math.min( currFromPos.length(), nodePath.length() ) ).copy();
								}

								if ( lastNode != node || ! path.equals( lastPath ) ) {
									// We have not saved this node/path pair yet.
									if ( node.isLeaf() ) {
										externalValues.add( behaviour );
										obs = externalKeys;
									}
									else {
										internalValues.add( behaviour );
										obs = internalKeys;
									}

									pathLength = (int)path.length();

									obs.writeLong( node.index, Long.SIZE );
									obs.writeDelta( pathLength );
									for( int i = 0; i < pathLength; i += Long.SIZE ) obs.writeLong( path.getLong( i, Math.min( i + Long.SIZE, pathLength) ), Math.min( Long.SIZE, pathLength - i ) );

									lastNode = node;
									lastPath = path;
									
									if ( ASSERTS ) {
										long key[] = new long[ ( pathLength + Long.SIZE - 1 ) / Long.SIZE + 1 ];
										key[ 0 ] = node.index;
										for( int i = 0; i < pathLength; i += Long.SIZE ) key[ i / Long.SIZE + 1 ] = path.getLong( i, Math.min( i + Long.SIZE, pathLength ) );
										if ( node.isLeaf() ) externalTestFunction.put( LongArrayBitVector.wrap( key, pathLength + Long.SIZE ), behaviour );
										else internalTestFunction.put( LongArrayBitVector.wrap( key, pathLength + Long.SIZE ), behaviour );
									}

									if ( DEBUG ) {
										System.err.println( "Computed " + ( node.isLeaf() ? "leaf " : "" ) + "mapping <" + node.index + ", " + path + "> -> " + behaviour );
										System.err.println( internalTestFunction );
										System.err.println( externalTestFunction );
									}

								}
								if ( behaviour != FOLLOW ) break;

							}

							pos += nodePath.length() + 1;
							if ( pos > curr.length() ) break;
							node = curr.getBoolean( pos - 1 ) ? node.right : node.left;
							// Update stack
							len[ ++depth ] = pos;
							stack[ depth ] = node;
						}

						prev.replace( curr );
					}

					internalKeys.close();
					externalKeys.close();
				}
				else size = 0;
			}
			else {
				// No elements.
				this.root = null;
				this.size = this.numElements = 0;
				internalKeysFile = externalKeysFile = null;
			}
		}

		private void recToString( final Node n, final MutableString printPrefix, final MutableString result, final MutableString path, final int level ) {
			if ( n == null ) return;
			
			result.append( printPrefix ).append( '(' ).append( level ).append( ')' );
			
			if ( n.path != null ) {
				path.append( n.path );
				result.append( " path:" ).append( n.path );
			}

			result.append( '\n' );
			
			path.append( '0' );
			recToString( n.left, printPrefix.append( '\t' ).append( "0 => " ), result, path, level + 1 );
			path.charAt( path.length() - 1, '1' ); 
			recToString( n.right, printPrefix.replace( printPrefix.length() - 5, printPrefix.length(), "1 => "), result, path, level + 1 );
			path.delete( path.length() - 1, path.length() ); 
			printPrefix.delete( printPrefix.length() - 6, printPrefix.length() );
			
			path.delete( (int)( path.length() - n.path.length() ), path.length() );
		}
		
		public String toString() {
			MutableString s = new MutableString();
			recToString( root, new MutableString(), s, new MutableString(), 0 );
			return s.toString();
		}

	}
	
	/** Creates a partial compacted trie using given elements, bucket size and transformation strategy.
	 * 
	 * @param elements the elements among which the trie must be able to rank.
	 * @param bucketSize the size of a bucket.
	 * @param transformationStrategy a transformation strategy that must turn the elements in <code>elements</code> into a list of
	 * distinct, lexicographically increasing (in iteration order) bit vectors.
	 */
	public HollowTrieDistributor( final Iterable<? extends T> elements, final int bucketSize, final TransformationStrategy<? super T> transformationStrategy ) throws IOException {
		this( elements, bucketSize, transformationStrategy, null );
	}

	/** Creates a partial compacted trie using given elements, bucket size, transformation strategy, and temporary directory.
	 * 
	 * @param elements the elements among which the trie must be able to rank.
	 * @param bucketSize the size of a bucket.
	 * @param transformationStrategy a transformation strategy that must turn the elements in <code>elements</code> into a list of
	 * distinct, lexicographically increasing (in iteration order) bit vectors.
	 * @param tempDir the directory where temporary files will be created, or <code>for the default directory</code>.
	 */
	public HollowTrieDistributor( final Iterable<? extends T> elements, final int bucketSize, final TransformationStrategy<? super T> transformationStrategy, final File tempDir ) throws IOException {
		this.transformationStrategy = transformationStrategy;
		final IntermediateTrie<T> intermediateTrie = new IntermediateTrie<T>( elements, bucketSize, transformationStrategy, tempDir );

		size = intermediateTrie.size;
		internalTestFunction = intermediateTrie.internalTestFunction;
		externalTestFunction = intermediateTrie.externalTestFunction;
		
		final int numInternalKeys = intermediateTrie.internalValues.size();
		final int numExternalKeys = intermediateTrie.externalValues.size();
		
		trie = LongArrayBitVector.getInstance( size );
		
		ObjectArrayList<IntermediateTrie.Node> queue = new ObjectArrayList<IntermediateTrie.Node>();
		IntArrayList skips = new IntArrayList();
		int p = 0;
		
		// Turn the compacted trie into a hollow trie.
		if ( intermediateTrie.root != null ) {
			queue.add( intermediateTrie.root );

			if ( DDEBUG ) System.err.println( intermediateTrie );

			IntermediateTrie.Node n;

			while( p < queue.size() ) {
				n = queue.get( p );
				if ( ! n.isLeaf() ) skips.add( (int)n.path.length() );
				trie.add( ! n.isLeaf() );
				if ( ASSERTS ) assert ( n.left == null ) == ( n.right == null );
				if ( n.left != null ) queue.add( n.left );
				if ( n.right != null ) queue.add( n.right );
				p++;
			}

			if ( ASSERTS ) assert p == size : p + " != " + size;
		}
		
		rank9 = new Rank9( trie );
		select = new SimpleSelect( trie );
		this.skips = new EliasFanoLongBigList( skips );
		skips = null;
		queue = null;
		
		LOGGER.info( "Bits per skip: " + bitsPerSkip() );

		/** A class iterating over the temporary files produced by the intermediate trie. */
		class IterableStream implements Iterable<BitVector> {
			private InputBitStream ibs;
			private int n;
			private Object2LongFunction<BitVector> test;
			private LongBigList values;
			
			public IterableStream( final InputBitStream ibs, final int n, final Object2LongFunction<BitVector> testFunction, final LongBigList testValues ) {
				this.ibs = ibs;
				this.n = n;
				this.test = testFunction;
				this.values = testValues;
			}

			public Iterator<BitVector> iterator() {
				try {
					ibs.position( 0 );
					return new AbstractObjectIterator<BitVector>() {
						private int pos = 0;
						
						public boolean hasNext() {
							return pos < n;
						}

						public BitVector next() {
							if ( ! hasNext() ) throw new NoSuchElementException();
							try {
								final long index = ibs.readLong( 64 );
								final int pathLength = ibs.readDelta();
								final long key[] = new long[ ( ( pathLength + Long.SIZE - 1 ) / Long.SIZE + 1 ) ];
								key[ 0 ] = index;
								for( int i = 0; i < ( pathLength + Long.SIZE - 1 ) / Long.SIZE; i++ ) key[ i + 1 ] = ibs.readLong( Math.min( Long.SIZE, pathLength - i * Long.SIZE ) );
								
								if ( DEBUG ) {
									System.err.println( "Adding mapping <" + index + ", " +  LongArrayBitVector.wrap( key, pathLength + Long.SIZE ).subVector( Long.SIZE ) + "> -> " + values.getLong( pos ));
									System.err.println(  LongArrayBitVector.wrap( key, pathLength + Long.SIZE ) );
								}

								if ( ASSERTS ) assert test.getLong( LongArrayBitVector.wrap( key, pathLength + Long.SIZE ) ) == values.getLong( pos ) : test.getLong( LongArrayBitVector.wrap( key, pathLength + Long.SIZE ) ) + " != " + values.getLong( pos ) ;
								
								pos++;
								return LongArrayBitVector.wrap( key, pathLength + Long.SIZE );
							}
							catch ( IOException e ) {
								throw new RuntimeException( e );
							}
						}
					};
				}
				catch ( IOException e ) {
					throw new RuntimeException( e );
				}
			}
		};
		
		internalBehaviour = new MWHCFunction<BitVector>( new IterableStream( new InputBitStream( intermediateTrie.internalKeysFile ), numInternalKeys, internalTestFunction, intermediateTrie.internalValues ), TransformationStrategies.identity(), intermediateTrie.internalValues, 2 );
		externalBehaviour = new MWHCFunction<BitVector>( new IterableStream( new InputBitStream( intermediateTrie.externalKeysFile ), numExternalKeys, externalTestFunction, intermediateTrie.externalValues ), TransformationStrategies.identity(), intermediateTrie.externalValues, 1 );
		
		LOGGER.debug( "Forecast three-way behaviour-function bit cost: " + 2 * intermediateTrie.numElements * GAMMA );
		LOGGER.debug( "Actual three-way behaviour-function bit cost: " + internalBehaviour.numBits() );
		LOGGER.debug( "Forecast two-way behaviour-function bit cost: " + intermediateTrie.size * GAMMA );
		LOGGER.debug( "Actual two-way behaviour-function bit cost: " + externalBehaviour.numBits() );
		LOGGER.debug( "Forecast behaviour-functions bit cost: " + ( 2 * intermediateTrie.numElements * GAMMA + intermediateTrie.size * GAMMA ) );
		LOGGER.debug( "Actual behaviour-functions bit cost: " + ( externalBehaviour.numBits() + internalBehaviour.numBits() ) );
		
		intermediateTrie.internalKeysFile.delete();
		intermediateTrie.externalKeysFile.delete();
		
		if ( ASSERTS ) {
			if ( size > 0 ) {
				Iterator<BitVector>iterator = TransformationStrategies.wrap( elements.iterator(), transformationStrategy );
				int c = 0;
				while( iterator.hasNext() ) {
					BitVector curr = iterator.next();
					if ( DEBUG ) System.err.println( "Checking element number " + c + ( ( c + 1 ) % bucketSize == 0 ? " (bucket)" : "" ));
					long t = getLong( curr );
					assert t == c / bucketSize : t + " != " + c / bucketSize;
					c++;
				}		
			}
		}
	}
	
	
	@SuppressWarnings("unchecked")
	public long getLong( final Object o ) {
		if ( size == 0 ) return 0;
		final BitVector bitVector = transformationStrategy.toBitVector( (T)o ).fast();
		LongArrayBitVector key = LongArrayBitVector.getInstance();
		long p = 0, r = 0, length = bitVector.length(), index = 0, a = 0, b = 0, t;
		int s = 0, skip = 0, behaviour;
		boolean isInternal;
			
		if ( DEBUG ) System.err.println( "Distributing " + bitVector + "\ntrie:" + trie );
		
		for(;;) {
			isInternal = trie.getBoolean( p );
			if ( isInternal ) skip = (int)skips.getLong( r );
			//System.err.println( "Interrogating" + ( trie.getBoolean( p ) ? "" : " leaf" ) + " <" + p + ", " + bitVector.subVector( s, Math.min( length, s + skip ) ) + "> (skip: " + skip + ")" );
			behaviour = isInternal ? (int)internalBehaviour.getLong( key.length( 0 ).append( p, Long.SIZE ).append( bitVector.subVector( s, Math.min( length, s + skip ) ) ) )
					: (int)externalBehaviour.getLong( key.length( 0 ).append( p, Long.SIZE ).append( bitVector.subVector( s ) ) );
			
			if ( ASSERTS ) {
				final long result; 
				if ( isInternal ) result = internalTestFunction.getLong( key.length( 0 ).append( p, Long.SIZE ).append( bitVector.subVector( s, Math.min( length, s + skip ) ) ) );
				else result = externalTestFunction.getLong( key.length( 0 ).append( p, Long.SIZE ).append( bitVector.subVector( s ) ) ); 
				if ( result != -1 ) assert result == behaviour : result + " != " + behaviour; 
			}
			
			if ( DEBUG ) System.err.println( "Exit behaviour: " + behaviour );

			if ( behaviour != FOLLOW || ! isInternal || ( s += skip ) >= length ) break;

			if ( DEBUG ) System.err.print( "Turning " + ( bitVector.getBoolean( s ) ? "right" : "left" ) + " at bit " + s + "... " );
			
			if ( bitVector.getBoolean( s ) ) p = 2 * r + 2;
			else p = 2 * r + 1;
			
			t = 2 * rank9.rank( a, b + 1 );
			a = b + 1;
			b += t;

			//System.err.println( "Rank incr: " + p + " " + a + " " +  rank9.rank( a, p ));
			
			index += p - a - rank9.rank( a, p );

			//System.err.println( a + " " + b + " " + p + " " + index );
			
			if ( ASSERTS ) assert p < trie.length();

			r = rank9.rank( p + 1 ) - 1;
			
			s++;
		}
		
		if ( behaviour == LEFT ) {
			if ( DEBUG ) System.err.println( "Moving on the leftmost path at p=" + p + " (index=" + index + ")" );
			for(;;) {
				if ( ! trie.getBoolean( p ) ) break;

				t = 2 * rank9.rank( a, b + 1 );
				a = b + 1;
				b += t;

				p = 2 * r + 1;
				index += p - a - rank9.rank( a, p );
				r = rank9.rank( p + 1 ) - 1;

			}
		}
		else if ( behaviour == RIGHT ) {
			index++;
			
			if ( DEBUG ) System.err.println( "Moving on the rightmost path at p=" + p + " (index=" + index + ")" );
			for(;;) {
				if ( ! trie.getBoolean( p ) ) break;

				t = 2 * rank9.rank( a, b + 1 );
				a = b + 1;
				b += t;

				p = 2 * r + 2;
				index += p - a - rank9.rank( a, p );
				r = rank9.rank( p + 1 ) - 1;

			}
		}
		
		// System.out.println();
		// Complete computation of leaf index
		
		if ( DEBUG ) System.err.println( "Completing at p=" + p + " (index=" + index + ")" );
		
		for(;;) {
			r = rank9.rank( p + 1 );
			if ( r == 0 || ( p = select.select( r - 1 ) ) < a ) break;
			// We follow the leftmost path.
			p = r * 2;
			
			t = 2 * rank9.rank( a, b + 1 );
			a = b + 1;
			b += t;
			
			index += p - a + 1 - rank9.rank( a, p + 1 );
			
			//System.err.println( "Scanning; " + a + " " + b + " " + p + " " + index );
		}

		if ( DEBUG ) System.err.println( "Returning " + index );
		
		return index;	
	}
	
	public long numBits() {
		return trie.length() + rank9.numBits() + skips.numBits() + select.numBits() + internalBehaviour.numBits() + externalBehaviour.numBits() + transformationStrategy.numBits();
	}
	
	public boolean containsKey( Object o ) {
		return true;
	}

	public int size() {
		return size;
	}
	
	public double bitsPerSkip() {
		return (double)skips.numBits() / skips.length();
	}
}
