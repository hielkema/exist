/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-04 The eXist Project
 *  http://exist-db.org
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id$
 */
package org.exist.storage;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.dom.AttrImpl;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentSet;
import org.exist.dom.ElementImpl;
import org.exist.dom.ExtArrayNodeSet;
import org.exist.dom.NodeImpl;
import org.exist.dom.NodeProxy;
import org.exist.dom.NodeSet;
import org.exist.dom.TextImpl;
import org.exist.dom.XMLUtil;
import org.exist.storage.btree.BTreeCallback;
import org.exist.storage.btree.BTreeException;
import org.exist.storage.btree.DBException;
import org.exist.storage.btree.IndexQuery;
import org.exist.storage.btree.Value;
import org.exist.storage.index.BFile;
import org.exist.storage.io.VariableByteArrayInput;
import org.exist.storage.io.VariableByteInput;
import org.exist.storage.io.VariableByteOutputStream;
import org.exist.storage.lock.Lock;
import org.exist.util.ByteConversion;
import org.exist.util.LockException;
import org.exist.util.LongLinkedList;
import org.exist.util.ReadOnlyException;
import org.exist.util.UTF8;
import org.exist.util.ValueOccurrences;
import org.exist.util.XMLString;
import org.exist.xquery.Constants;
import org.exist.xquery.TerminatedException;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;

/**
 * Maintains an index on typed node values.
 * 
 * TODO: Check correct types during validation.
 * 
 * In the BTree single BFile, the keys are :
 * (collectionId, indexType, indexData)
 * and the values are : gid1, gid2-gid1, ...
 * <b></b>
 * <p>Algorithm:</p>
 * When a node is stored, an entry is added or updated in the {@link #pending} map, 
 * with given String content and basic type as key.
 * This way, the index entries are easily put in the persistent BFile storage by 
 * {@link #flush()} .
 * 
 * @author wolf
 */
public class NativeValueIndex implements ContentLoadingObserver {

    private final static Logger LOG = Logger.getLogger(NativeValueIndex.class);
    
	/** Data base broker associated to this value index - 1 to 1 association */
	DBBroker broker;
	
	/** Data storage associated to this value index - 1 to 1 association */
    protected BFile db;
    
	/** Pending modifications; the keys are AtomicValue objects implementing Indexable 
	 * (StringValue or numeric values, IntegerValue etc), 
	 * which are the index entries,
	 * and the values are LongLinkedList objects 
	 * whose entries are gid (global identifiers) matching the index entries.
	 * Do not confuse these keys with the keys used in persistent storage, created with
	 * {@link Indexable#serialize(short) */
    protected TreeMap pending = new TreeMap();
    
	/** the current document */
    private DocumentImpl doc;
    
	/** work Output Stream; it is cleared before each use */
    private VariableByteOutputStream os = new VariableByteOutputStream();
    
    protected boolean caseSensitive = true;
    
    public NativeValueIndex(DBBroker broker, BFile valuesDb) {
        this.broker = broker;
        this.db = valuesDb;
        Boolean caseOpt = (Boolean) broker.getConfiguration().getProperty("indexer.case-sensitive");
        if (caseOpt != null)
            caseSensitive = caseOpt.booleanValue();
    }
    
	/* (non-Javadoc)
	 * @see org.exist.storage.IndexGenerator#storeElement(int, org.exist.dom.ElementImpl, java.lang.String)
	 */
    public void storeElement(int xpathType, ElementImpl node, String content) {
        AtomicValue atomic = convertToAtomic(xpathType, content);
        if(atomic == null)
            return;		// skip
        LongLinkedList buf;
        if (pending.containsKey(atomic))
            buf = (LongLinkedList) pending.get(atomic);
        else {
            buf = new LongLinkedList();
            pending.put(atomic, buf);
        }
        buf.add(node.getGID());
    }
    
	/* (non-Javadoc)
	 * @see org.exist.storage.IndexGenerator#storeAttribute(org.exist.storage.RangeIndexSpec, org.exist.dom.AttrImpl)
	 */
    public void storeAttribute(RangeIndexSpec spec, AttrImpl node) {
        AtomicValue atomic = convertToAtomic(spec.getType(), node.getValue());
        if(atomic == null)
            return;		// skip
        LongLinkedList buf;
        if (pending.containsKey(atomic))
            buf = (LongLinkedList) pending.get(atomic);
        else {
            buf = new LongLinkedList();
            pending.put(atomic, buf);
        }
        buf.add(node.getGID());
    }
    
	/* (non-Javadoc)
	 * @see org.exist.storage.IndexGenerator#setDocument(org.exist.dom.DocumentImpl)
	 */
    public void setDocument(DocumentImpl document) {
        this.doc = document;
    }
    
	/* (non-Javadoc)
	 * @see org.exist.storage.IndexGenerator#flush()
	 */
    public void flush() {
        if (pending.size() == 0) return;
        Indexable indexable;
        LongLinkedList idList;
        long ids[];
        int len;
        Value ref;
        Map.Entry entry;
        long prevId;
        long cid;
        short collectionId = doc.getCollection().getId();
        Lock lock = db.getLock();
        try {
            for (Iterator i = pending.entrySet().iterator(); i.hasNext();) {
                entry = (Map.Entry) i.next();
                indexable = (Indexable) entry.getKey();
                idList = (LongLinkedList) entry.getValue();
                os.clear();
                ids = idList.getData();
                Arrays.sort(ids);
                len = ids.length;
                os.writeInt(doc.getDocId());
                os.writeInt(len);
                prevId = 0;
                for (int j = 0; j < len; j++) {
                    cid = ids[j] - prevId;
                    prevId = ids[j];
                    os.writeLong(cid);
                }
                ref = new Value(indexable.serialize(collectionId, caseSensitive));
                try {
                    lock.acquire(Lock.WRITE_LOCK);
                    if (db.append(ref, os.data()) < 0) {
                        LOG.warn("could not save index for value");
                        continue;
                    }
                } catch (LockException e) {
                    LOG.error("could not acquire lock on values.dbx", e);
                } catch (IOException e) {
                    LOG.error("io error while writing value index.", e);
                } finally {
                    lock.release();
                }
            }
        } catch (ReadOnlyException e) {
            LOG.warn("database is read-only");
            return;
        }
        pending.clear();
    }
    
	/* (non-Javadoc)
	 * @see org.exist.storage.IndexGenerator#sync()
	 */
    public void sync() {
        Lock lock = db.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            try {
                db.flush();
            } catch (DBException dbe) {
                LOG.warn(dbe);
            }
        } catch (LockException e) {
            LOG.warn("could not acquire lock for values.dbx", e);
        } finally {
            lock.release();
        }
    }
    
    /* (non-Javadoc)
	 * @see org.exist.storage.IndexGenerator#dropIndex(org.exist.collections.Collection)
	 */
    public void dropIndex(Collection collection) {
        LOG.debug("removing elements ...");
        Value ref = new ElementValue(collection.getId());
        IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
        Lock lock = db.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            db.removeAll(query);
        } catch (LockException e) {
            LOG.error("could not acquire lock on elements index", e);
        } catch (BTreeException e) {
            LOG.warn(e.getMessage(), e);
        } catch (IOException e) {
            LOG.warn(e.getMessage(), e);
        } finally {
            lock.release();
        }
    }
    
    /* (non-Javadoc)
	 * @see org.exist.storage.IndexGenerator#dropIndex(org.exist.dom.DocumentImpl)
	 */
    public void dropIndex(DocumentImpl doc) throws ReadOnlyException {
        //	  drop element-index
        short collectionId = doc.getCollection().getId();
        Value ref = new ElementValue(collectionId);
        IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
        Lock lock = db.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            ArrayList elements = db.findKeys(query);

            Value key;
            Value value;
            byte[] data;
            VariableByteArrayInput is;
            VariableByteOutputStream os;
            int len;
            int docId;
            long delta;
//            long address;
            boolean changed;
            for (int i = 0; i < elements.size(); i++) {
                key = (Value) elements.get(i);
                value = db.get(key);
                data = value.getData();
                is = new VariableByteArrayInput(data);
                os = new VariableByteOutputStream();
                changed = false;
                try {
                    while (is.available() > 0) {
                        docId = is.readInt();
                        len = is.readInt();
                        // copy data to new buffer if not in given document
						if (docId != doc.getDocId()) {
                            os.writeInt(docId);
                            os.writeInt(len);
                            for (int j = 0; j < len; j++) {
                                delta = is.readLong();
                                os.writeLong(delta);
                            }
                        } else {
                            changed = true;
                            // skip
                            is.skip(len);
                        }
                    }
                } catch (EOFException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("removeDocument(String) - eof", e);
                    }
                } catch (IOException e) {
                    LOG.warn("removeDocument(String) " + e.getMessage(), e);
                }
                if (changed) {
                    if (os.data().size() == 0) {
                        db.remove(key);
                    } else {
                        if (db.put(key, os.data()) < 0)
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("removeDocument() - "
                                        + "could not save value index");
                            }
                    }
                }
            }
        } catch (LockException e) {
            LOG.warn("removeDocument(String) - "
                    + "could not acquire lock on values.dbx", e);
        } catch (TerminatedException e) {
            LOG.warn("method terminated", e);
        } catch (BTreeException e) {
            LOG.warn(e.getMessage(), e);
        } catch (IOException e) {
            LOG.warn(e.getMessage(), e);
        } finally {
            lock.release();
        }
    }
    
	/* (non-Javadoc)
	 * @see org.exist.storage.IndexGenerator#reindex(org.exist.dom.DocumentImpl, org.exist.dom.NodeImpl)
	 */
    public void reindex(DocumentImpl oldDoc, NodeImpl node) {
        if (pending.size() == 0) return;
        Lock lock = db.getLock();
        Map.Entry entry;
        Indexable indexable;
        LongLinkedList oldList, idList;
        VariableByteInput is = null;
        int len, docId;
//        byte[] data;
        Value ref;
//        Value val;
//        short sym, nsSym;
        short collectionId = oldDoc.getCollection().getId();
        long gid, prevId, cid, address;
        long ids[];
        try {
            // iterate through elements
            for (Iterator i = pending.entrySet().iterator(); i.hasNext();) {
                entry = (Map.Entry) i.next();
                indexable = (Indexable) entry.getKey();
                idList = (LongLinkedList) entry.getValue();
                ref = new Value(indexable.serialize(collectionId, caseSensitive));
                
                // try to retrieve old index entry for the element
                try {
                    lock.acquire(Lock.WRITE_LOCK);
                    is = db.getAsStream(ref);
                    os.clear();
                    oldList = new LongLinkedList();
                    if (is != null) {
                        // add old entries to the new list
                        try {
                            while (is.available() > 0) {
                                docId = is.readInt();
                                len = is.readInt();
                                if (docId != oldDoc.getDocId()) {
                                    // section belongs to another document:
                                    // copy data to new buffer
                                    os.writeInt(docId);
                                    os.writeInt(len);
                                    is.copyTo(os, len);
                                } else {
                                    // copy nodes to new list
                                    gid = 0;
                                    for (int j = 0; j < len; j++) {
                                        gid += is.readLong();
                                        if (node == null
                                                && oldDoc.getTreeLevel(gid) < oldDoc
                                                .reindexRequired()) {
                                            idList.add(gid);
                                        } else if (node != null
                                                && (!XMLUtil.isDescendant(oldDoc,
                                                        node.getGID(), gid))) {
                                            oldList.add(gid);
                                        }
                                    }
                                }
                            }
                        } catch (EOFException e) {
                        } catch (IOException e) {
                            LOG.error("io-error while updating index for value", e);
                        }
                    }
                    // write out the updated list
                    ids = idList.getData();
	                Arrays.sort(ids);
	                len = ids.length;

                    os.writeInt(doc.getDocId());
                    os.writeInt(len);
                    prevId = 0;
                    for (int j = 0; j < len; j++) {
                        cid = ids[j] - prevId;
                        prevId = ids[j];
                        os.writeLong(cid);
                    }
                    if (is == null)
                        db.put(ref, os.data());
                    else {
                        address = ((BFile.PageInputStream) is).getAddress();
                        db.update(address, ref, os.data());
                    }
                } catch (LockException e) {
                    LOG.error("could not acquire lock for value index", e);
                    return;
                } catch (IOException e) {
                    LOG.error("io error while reindexing", e);
                    is = null;
                } finally {
                    lock.release(Lock.WRITE_LOCK);
                }
            }
        } catch (ReadOnlyException e) {
            LOG.warn("database is read only");
        }
        pending.clear();
    }
    
	/* (non-Javadoc)
	 * @see org.exist.storage.IndexGenerator#remove()
	 */
    public void remove() {
        if (pending.size() == 0) return;
        Lock lock = db.getLock();
        Map.Entry entry;
        Indexable indexable;
        LongLinkedList newList, idList;
        long[] ids;
        VariableByteArrayInput is;
        int len, docId;
        byte[] data;
        Value ref;
        Value val;
//        short sym, nsSym;
        short collectionId = doc.getCollection().getId();
        long delta, last, gid;
        try {
            // iterate through pending elements
            for (Iterator i = pending.entrySet().iterator(); i.hasNext();) {
                try {
                    lock.acquire(Lock.WRITE_LOCK);
                    entry = (Map.Entry) i.next();
                    indexable = (Indexable) entry.getKey();
                    idList = (LongLinkedList) entry.getValue();
                    ref = new Value(indexable.serialize(collectionId, caseSensitive));
                    
                    val = db.get(ref);
                    os.clear();
                    newList = new LongLinkedList();
                    if (val != null) {
                        // add old entries to the new list
                        data = val.getData();
                        is = new VariableByteArrayInput(data);
                        try {
							// iterate through indexed nodes
                            while (is.available() > 0) {
                                docId = is.readInt();
                                len = is.readInt();
                                if (docId != doc.getDocId()) {
                                    // section belongs to another document:
                                    // copy data to new buffer
                                    os.writeInt(docId);
                                    os.writeInt(len);
                                    try {
                                        is.copyTo(os, len);
                                    } catch(EOFException e) {
                                        LOG.error("EOF while copying: expected: " + len);
                                    }
                                } else {
                                    // copy nodes to new list
                                    last = 0;
                                    for (int j = 0; j < len; j++) {
                                        delta = is.readLong();
                                        gid = last + delta;
                                        last = gid;
                                        if (!idList.contains(gid)) {
                                            newList.add(gid);
                                        }
                                    }
                                }
                            }
                        } catch (EOFException e) {
                            LOG
                            .error("end-of-file while updating value index", e);
                        } catch (IOException e) {
                            LOG.error("io-error while updating value index", e);
                        }
                    }
                    // write out the updated list
                    ids = newList.getData();
	                Arrays.sort(ids);
	                len = ids.length;
                    os.writeInt(doc.getDocId());
                    os.writeInt(len);
                    last = 0;
                    for (int j = 0; j < len; j++) {
                        delta = ids[j] - last;
                        last = ids[j];
                        os.writeLong(delta);
                    }
                    if (val == null) {
                    	db.put(ref, os.data());
                    } else {
                    	db.update(val.getAddress(), ref, os.data());
                    }
                } catch (LockException e) {
                    LOG.error("could not acquire lock on elements", e);
                } finally {
                    lock.release();
                }
            }
        } catch (ReadOnlyException e) {
            LOG.warn("database is read only");
        }
        pending.clear();
    }
    
	/** find
	 * @param relation binary operator used for the comparison
	 * @param value right hand comparison value */
    public NodeSet find(int relation, DocumentSet docs, NodeSet contextSet, Indexable value) 
    throws TerminatedException {
        int idxOp =  checkRelationOp(relation);
        NodeSet result = new ExtArrayNodeSet();
        SearchCallback callback = new SearchCallback(docs, contextSet, result, true);
        Lock lock = db.getLock();
        for (Iterator iter = docs.getCollectionIterator(); iter.hasNext();) {
			Collection collection = (Collection) iter.next();
			short collectionId = collection.getId();
			byte[] key = value.serialize(collectionId, caseSensitive);
			IndexQuery query = new IndexQuery(idxOp, new Value(key));
            Value prefix = getPrefixValue(value.getType(), collectionId);
			try {
				lock.acquire();
				try {
					db.query(query, prefix, callback);
				} catch (IOException ioe) {
					LOG.debug(ioe);
				} catch (BTreeException bte) {
					LOG.debug(bte);
				}
			} catch (LockException e) {
				LOG.debug(e);
			} finally {
				lock.release();
			}
        }
        return result;
    }
    
    /**
     * Returns a key containing type and collectionId.
     */
    private Value getPrefixValue(int type, short collectionId) {
        byte[] data = new byte[3];
        ByteConversion.shortToByte(collectionId, data, 0);
        data[2] = (byte) type;
        return new Value(data);
    }
    
    public NodeSet match(DocumentSet docs, NodeSet contextSet, String expr, int type)
    throws TerminatedException, EXistException {
        return match(docs, contextSet, expr, type, 0, true);
    }
    
	/** Regular expression search
	 * @param type  like type argument for {@link RegexMatcher} constructor
	 * @param flags like flags argument for {@link RegexMatcher} constructor
	 *  */
    public NodeSet match(DocumentSet docs, NodeSet contextSet, String expr, int type, int flags, boolean caseSensitiveQuery)
    throws TerminatedException, EXistException {
    	// if the regexp starts with a char sequence, we restrict the index scan to entries starting with
    	// the same sequence. Otherwise, we have to scan the whole index.
        StringValue startTerm = null;
        if (expr.startsWith("^") && caseSensitiveQuery == caseSensitive) {
        	StringBuffer term = new StringBuffer();
    		for (int j = 1; j < expr.length(); j++)
    			if (Character.isLetterOrDigit(expr.charAt(j)))
    				term.append(expr.charAt(j));
    			else
    				break;
    		if(term.length() > 0) {
    			startTerm = new StringValue(term.toString());
    		}
        }
        
		TermMatcher comparator = new RegexMatcher(expr, type, flags);
        NodeSet result = new ExtArrayNodeSet();
        RegexCallback callback = new RegexCallback(docs, contextSet, result, comparator);
        Lock lock = db.getLock();
        for (Iterator iter = docs.getCollectionIterator(); iter.hasNext();) {
			Collection collection = (Collection) iter.next();
			short collectionId = collection.getId();
			byte[] key;
			if(startTerm != null)
				key = startTerm.serialize(collectionId, caseSensitive);
			else {
				key = new byte[3];
				ByteConversion.shortToByte(collectionId, key, 0);
				key[2] = (byte) Type.STRING;
			}
			IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, new Value(key));
			try {
				lock.acquire();
				try {
					db.query(query, callback);
				} catch (IOException ioe) {
					LOG.debug(ioe);
				} catch (BTreeException bte) {
					LOG.debug(bte);
				}
			} catch (LockException e) {
				LOG.debug(e);
			} finally {
				lock.release();
			}
        }
        return result;
    }
    
    public ValueOccurrences[] scanIndexKeys(DocumentSet docs, NodeSet contextSet,
            Indexable start) {
        long t0 = System.currentTimeMillis();
        final Lock lock = db.getLock();
        short collectionId;
        Collection current;
        IndexQuery query;
        int type = ((Item) start).getType();
        boolean stringType = Type.subTypeOf(type, Type.STRING);
        IndexScanCallback cb = new IndexScanCallback(docs, contextSet, type);
        for (Iterator i = docs.getCollectionIterator(); i.hasNext();) {
            current = (Collection) i.next();
            collectionId = current.getId();
            byte[] startKey = start.serialize(collectionId, caseSensitive);
            int op = stringType ? IndexQuery.TRUNC_RIGHT : IndexQuery.GEQ;
            query = new IndexQuery(op, new Value(startKey));
            Value prefix = getPrefixValue(start.getType(), collectionId);
            try {
                lock.acquire();
                if (stringType)
                    db.query(query, cb);
                else
                    db.query(query, prefix, cb);
            } catch (LockException e) {
                LOG.warn("cannot get lock on words", e);
            } catch (IOException e) {
                LOG.warn("error while reading words", e);
            } catch (BTreeException e) {
                LOG.warn("error while reading words", e);
            } catch (TerminatedException e) {
                LOG.warn("Method terminated", e);
            } finally {
                lock.release();
            }
        }
        Map map = cb.map;
        ValueOccurrences[] result = new ValueOccurrences[map.size()];
        LOG.debug("Found " + result.length + " in " + (System.currentTimeMillis() - t0));
        return (ValueOccurrences[]) map.values().toArray(result);
    }

    protected int checkRelationOp(int relation) {
        int indexOp;
        switch(relation) {
        	case Constants.LT:
        	    indexOp = IndexQuery.LT;
        		break;
        	case Constants.LTEQ:
        	    indexOp = IndexQuery.LEQ;
        		break;
        	case Constants.GT:
        	    indexOp = IndexQuery.GT;
        		break;
        	case Constants.GTEQ:
        	    indexOp = IndexQuery.GEQ;
        		break;
        	case Constants.NEQ:
        	    indexOp = IndexQuery.NEQ;
        		break;
        	case Constants.EQ:
        	default:
        	    indexOp = IndexQuery.EQ;
        		break;
        }
        return indexOp;
    }
    
	/** compute a key for the "pending" map */
    private AtomicValue convertToAtomic(int xpathType, String value) {
        AtomicValue atomic = null;
        if(Type.subTypeOf(xpathType, Type.STRING)) {
            atomic = new StringValue(value);
        } else {
            try {
                atomic = new StringValue(value).convertTo(xpathType);
            } catch (XPathException e) {
                LOG.warn("Node value: '" + value + "' cannot be converted to type " + 
                        Type.getTypeName(xpathType));
            }
        }
        if(!(atomic instanceof Indexable)) {
            LOG.warn("The specified type: '" + Type.getTypeName(xpathType) +
            		"' and value '" + value + "'" +
                    " cannot be used as index key. It is null or does not implement interface Indexable.");
            atomic = null;
        }
        return atomic;        
    }
    
	/** TODO document */
    class SearchCallback implements BTreeCallback {
        
        DocumentSet docs;
        NodeSet contextSet;
        NodeSet result;
        boolean returnAncestor;
        
        public SearchCallback(DocumentSet docs, NodeSet contextSet, NodeSet result, boolean returnAncestor) {
            this.docs = docs;
            this.contextSet = contextSet;
            this.result = result;
            this.returnAncestor = returnAncestor;
        }
        
        /* (non-Javadoc)
         * @see org.dbxml.core.filer.BTreeCallback#indexInfo(org.dbxml.core.data.Value, long)
         */
        public boolean indexInfo(Value value, long pointer)
                throws TerminatedException {
            VariableByteInput is = null;
			try {
				is = db.getAsStream(pointer);
			} catch (IOException ioe) {
				LOG.warn(ioe.getMessage(), ioe);
			}
			if (is == null)
				return true;
			try {
                int sizeHint = -1;
                while (is.available() > 0) {
                	int docId = is.readInt();
                	int len = is.readInt();
                	if ((doc = docs.getDoc(docId)) == null
                			|| (contextSet != null && !contextSet.containsDoc(doc))) {
                		is.skip(len);
                		continue;
                	}
                	if (contextSet != null)
                		sizeHint = contextSet.getSizeHint(doc);
                	long gid = 0;
                	NodeProxy current, parent;
                	for (int j = 0; j < len; j++) {
                		gid = gid + is.readLong();
						
                		current = new NodeProxy(doc, gid);
						
                		// if a context set is specified, we can directly check if the
                		// matching node is a descendant of one of the nodes
                		// in the context set.
                		if (contextSet != null) {
                			parent = contextSet.parentWithChild(current, false, true, -1);
                			if (parent != null) {
                				result.add(returnAncestor ? parent : current, sizeHint);
                			}
                		// otherwise, we add all nodes without check
                		} else {
                			result.add(current, sizeHint);
                		}
                	}
                }
			} catch (EOFException e) {
			    // EOF is expected here
            } catch (IOException e) {
                LOG.warn("io error while reading index", e);
            }
            return false;
        }
    }
    
	/** TODO document */
    private class RegexCallback extends SearchCallback {
    	
    	private TermMatcher matcher;
    	private XMLString key = new XMLString(128);
        
    	public RegexCallback(DocumentSet docs, NodeSet contextSet, NodeSet result, TermMatcher matcher) {
    		super(docs, contextSet, result, true);
    		this.matcher = matcher;
    	}
    	
    	/**
		 * @see org.exist.storage.NativeValueIndex.SearchCallback#indexInfo(org.dbxml.core.data.Value, long)
		 */
		public boolean indexInfo(Value value, long pointer)
				throws TerminatedException {
            key.reuse();
            UTF8.decode(value.data(), value.start() + 3, value.getLength() - 3, key);
			if(matcher.matches(key)) {
				super.indexInfo(value, pointer);
			}
			return true;
		}
    }
    
private final class IndexScanCallback implements BTreeCallback{
        
        private DocumentSet docs;
        private NodeSet contextSet;
        private Map map = new TreeMap();
        private int type;
        
        IndexScanCallback(DocumentSet docs, NodeSet contextSet, int type) {
            this.docs = docs;
            this.contextSet = contextSet;
            this.type = type;
        }
        
        /* (non-Javadoc)
         * @see org.dbxml.core.filer.BTreeCallback#indexInfo(org.dbxml.core.data.Value, long)
         */
        public boolean indexInfo(Value key, long pointer)
                throws TerminatedException {
            AtomicValue atomic;
            try {
                atomic = ValueIndexFactory.deserialize(key.data(), key.start(), key.getLength());
                if (atomic.getType() != type)
                    return false;
            } catch (EXistException e) {
                LOG.warn(e.getMessage(), e);
                return true;
            }
            ValueOccurrences oc = (ValueOccurrences) map.get(atomic);
            
            VariableByteInput is = null;
            try {
                is = db.getAsStream(pointer);
            } catch (IOException ioe) {
                LOG.warn(ioe.getMessage(), ioe);
            }
            if (is == null)
                return true;
            try {
                int docId;
                int len;
                long gid;
                DocumentImpl doc;
                boolean include = true;
                boolean docAdded;
                while (is.available() > 0) {
                    docId = is.readInt();
                    len = is.readInt();
                    if ((doc = docs.getDoc(docId)) == null) {
                        is.skip(len);
                        continue;
                    }
                    docAdded = false;
                    gid = 0;
                    for (int j = 0; j < len; j++) {
                        gid += is.readLong();
                        if (contextSet != null) {
                            include = contextSet.parentWithChild(doc, gid, false, true) != null;
                        }
                        if (include) {
                            if (oc == null) {
                                oc = new ValueOccurrences(atomic);
                                map.put(atomic, oc);
                            }
                            if (!docAdded) {
                                oc.addDocument(doc);
                                docAdded = true;
                            }
                            oc.addOccurrences(1);
                        }
                    }
                }
            } catch(EOFException e) {
            } catch(IOException e) {
                LOG.warn("Exception while scanning index: " + e.getMessage(), e);
            }
            return true;
        }
    }

public void storeAttribute(AttrImpl node, NodePath currentPath, boolean fullTextIndexSwitch) {
	// TODO Auto-generated method stub
	
}

public void storeText(TextImpl node, NodePath currentPath, boolean fullTextIndexSwitch) {
	// TODO Auto-generated method stub
	
}

public void startElement(ElementImpl impl, NodePath currentPath, boolean index) {
	// TODO Auto-generated method stub
	
}

public void endElement(int xpathType, ElementImpl node, String content) {
	// TODO Auto-generated method stub
	
}

public void removeElement(ElementImpl node, NodePath currentPath, String content) {
	// TODO Auto-generated method stub
	
}

}
