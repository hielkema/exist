package org.exist.storage.store;

import java.io.IOException;
import java.util.Iterator;

import org.apache.log4j.Logger;
import org.dbxml.core.filer.BTree;
import org.dbxml.core.filer.BTreeException;
import org.exist.dom.DocumentImpl;
import org.exist.dom.NodeImpl;
import org.exist.dom.NodeProxy;
import org.exist.util.ByteConversion;
import org.exist.util.Lock;
import org.exist.util.LockException;

/**
 * Class NodeIterator is used to iterate over nodes in the DOM storage.
 * This implementation locks the DOM file to read the node and unlocks
 * it afterwards. It is thus safer than DOMFileIterator, since the node's
 * value will not change. 
 * 
 * @author wolf
 */
public final class NodeIterator implements Iterator {

	private final static Logger LOG = Logger.getLogger(NodeIterator.class);

	private DOMFile db = null;
	private NodeProxy node = null;
	private DocumentImpl doc = null;
	private int offset;
	private short lastTID = -1;
	private DOMFile.DOMPage p = null;
	private long page;
	private long startAddress = -1;
	private Object lockKey;

	public NodeIterator(Object lock, DOMFile db, NodeProxy node)
		throws BTreeException, IOException {
		this.db = db;
		this.doc = node.doc;
		if (-1 < node.getInternalAddress())
			startAddress = node.getInternalAddress();
		else
			this.node = node;
		lockKey = (lock == null ? this : lock);
	}

	public NodeIterator(Object lock, DOMFile db, DocumentImpl doc, long address) {
		this.db = db;
		this.doc = doc;
		this.startAddress = address;
		lockKey = (lock == null ? this : lock);
	}
	
	/**
	 *  Returns the internal virtual address of the node at the iterator's
	 * current position.
	 *
	 *@return    The currentAddress value
	 */
	public long currentAddress() {
		return StorageAddress.createPointer((int) page, ItemId.getId(lastTID));
	}

	/**
	 *  Are there more nodes to be read?
	 *
	 *@return    Description of the Return Value
	 */
	public boolean hasNext() {
		Lock lock = db.getLock();
		try {
			try {
				lock.acquire();
			} catch (LockException e) {
				return false;
			}
			if(gotoNextPosition()) {
				db.getPageBuffer().add(p);
				final DOMFile.DOMFilePageHeader ph = p.getPageHeader();
				if (offset < ph.getDataLength())
					return true;
				else if (ph.getNextDataPage() < 0)
					return false;
				else
					return true;
			}
		} catch (BTreeException e) {
			LOG.warn(e);
		} catch (IOException e) {
			LOG.warn(e);
		} finally {
			lock.release();
		}
		return false;
	}

	/**
	 *  Returns the next node in document order. 
	 */
	public Object next() {
		Lock lock = db.getLock();
		NodeImpl nextNode = null;
		try {
			try {
				lock.acquire(Lock.READ_LOCK);
			} catch (LockException e) {
				return null;
			}
			if(gotoNextPosition()) {
			    do {
					DOMFile.DOMFilePageHeader ph = p.getPageHeader();
					// next value larger than length of the current page?
					if (offset >= ph.getDataLength()) {
						// load next page in chain
						long nextPage = ph.getNextDataPage();
						if (nextPage < 0) {
							LOG.debug("bad link to next " + p.page.getPageInfo() + "; previous: " +
									ph.getPrevDataPage());
							return null;
						}
						page = nextPage;
						p = db.getCurrentPage(nextPage);
						db.addToBuffer(p);
						offset = 0;
					}
					// extract the tid
					lastTID = ByteConversion.byteToShort(p.data, offset);
					offset += 2;
					//	check if this is just a link to a relocated node
					if(ItemId.isLink(lastTID)) {
						// skip this
						long link = ByteConversion.byteToLong(p.data, offset);
						offset += 8;
						//System.out.println("skipping link on p " + page + " -> " + 
						//		StorageAddress.pageFromPointer(link));
						continue;
					}
					// read data length
					short l = ByteConversion.byteToShort(p.data, offset);
					offset += 2;
					if(ItemId.isRelocated(lastTID)) {
						// found a relocated node. Skip the next 8 bytes
						offset += 8;
					}
					//	overflow page? load the overflow value
					if(l == DOMFile.OVERFLOW) {
						l = 8;
						final long overflow = ByteConversion.byteToLong(p.data, offset);
						offset += 8;
						final byte[] odata = db.getOverflowValue(overflow);
						nextNode = NodeImpl.deserialize(odata, 0, odata.length, doc);
					// normal node
					} else {
						nextNode = NodeImpl.deserialize(p.data, offset, l, doc);
						offset += l;
					}
					nextNode.setInternalAddress(
						StorageAddress.createPointer((int) page, ItemId.getId(lastTID))
					);
					nextNode.setOwnerDocument(doc);
					// System.out.println("Next: " + nextNode.getNodeName() + " [" + page + "]");
			    } while(nextNode == null);
			}
			return nextNode;
		} catch (BTreeException e) {
			LOG.warn(e.getMessage(), e);
		} catch (IOException e) {
			LOG.warn(e.getMessage(), e);
		} finally {
			lock.release();
		}
		return null;
	}

	private boolean gotoNextPosition() throws BTreeException, IOException {
		//	position the iterator at the start of the first value
		if (node != null) {
			db.setOwnerObject(lockKey);
			final long addr = db.findValue(lockKey, node);
			if (addr == BTree.KEY_NOT_FOUND)
				return false;
			DOMFile.RecordPos rec = db.findRecord(addr);
			page = rec.page.getPageNum();
			p = rec.page;
			offset = rec.offset - 2;
			node = null;
		} else if (-1 < startAddress) {
			final DOMFile.RecordPos rec = db.findRecord(startAddress);
			page = rec.page.getPageNum();
			offset = rec.offset - 2;
			p = rec.page;
			startAddress = -1;
		} else if (page > -1)
			p = db.getCurrentPage(page);
		else
			return false;
		return true;
	}
	
	/**
	 * Remove the current node. This implementation just
	 * decrements the node count. It does not actually remove
	 * the node's value, but removes a page if
	 * node count == 0. Use this method only if you want to
	 * delete an entire document, not to remove a single node.
	 */
	public void remove() {
		throw new RuntimeException("remove() method not implemented");
	}

	/**
	 *  Reposition the iterator at the address of the proxy node.
	 *
	 *@param  node  The new to value
	 */
	public void setTo(NodeProxy node) {
		if (-1 < node.getInternalAddress()) {
			startAddress = node.getInternalAddress();
		} else {
			this.node = node;
		}
	}

	/**
	 *  Reposition the iterate at a given address.
	 *
	 *@param  address  The new to value
	 */
	public void setTo(long address) {
		this.startAddress = address;
	}
}