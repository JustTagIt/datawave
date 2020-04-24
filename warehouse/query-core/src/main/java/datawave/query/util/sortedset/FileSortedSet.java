package datawave.query.util.sortedset;

import datawave.webservice.query.exception.DatawaveErrorCode;
import datawave.webservice.query.exception.QueryException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A sorted set that can be persisted into a file and still be read in its persisted state. The set can always be re-loaded and then all operations will work as
 * expected. This will support null contained in the underlying sets iff a comparator is supplied that can handle null values.
 *
 * The persisted file will contain the serialized entries, followed by the actual size.
 *
 * @param <E>
 */
public abstract class FileSortedSet<E> implements SortedSet<E>, Cloneable {
    private static Logger log = Logger.getLogger(FileSortedSet.class);
    protected boolean persisted = false;
    protected E[] range;
    protected SortedSet<E> set = null;
    // A null entry placeholder
    public static final NullObject NULL_OBJECT = new NullObject();
    
    // The file handler that handles the underlying io
    public TypedSortedSetFileHandler handler;
    // The sort set factory
    public FileSortedSetFactory factory;
    
    /**
     * A class that represents a null object within the set
     * 
     * 
     * 
     */
    public static class NullObject implements Serializable {
        private static final long serialVersionUID = -5528112099317370355L;
        
    }
    
    /**
     * Create a file sorted set from another one
     * 
     * @param other
     */
    public FileSortedSet(FileSortedSet<E> other) {
        this.handler = other.handler;
        this.factory = other.factory;
        this.set = new TreeSet<>(other.set);
        this.persisted = other.persisted;
        this.range = other.range;
    }
    
    /**
     * Create a file sorted subset from another one
     * 
     * @param other
     * @param from
     * @param to
     */
    public FileSortedSet(FileSortedSet<E> other, E from, E to) {
        this(other);
        if (from != null || to != null) {
            if (persisted) {
                this.range = (E[]) new Object[] {getStart(from), getEnd(to)};
            } else if (to == null) {
                this.set = this.set.tailSet(from);
            } else if (from == null) {
                this.set = this.set.headSet(to);
            } else {
                this.set = this.set.subSet(from, to);
            }
        }
    }
    
    /**
     * Create a persisted sorted set
     * 
     * @param handler
     * @param factory
     * @param persisted
     */
    public FileSortedSet(TypedSortedSetFileHandler handler, FileSortedSetFactory factory, boolean persisted) {
        this.handler = handler;
        this.factory = factory;
        this.set = new TreeSet<>();
        this.persisted = persisted;
    }
    
    /**
     * Create a persisted sorted set
     * 
     * @param comparator
     * @param handler
     * @param factory
     *            ;
     * @param persisted
     */
    public FileSortedSet(Comparator<? super E> comparator, TypedSortedSetFileHandler handler, FileSortedSetFactory factory, boolean persisted) {
        this.handler = handler;
        this.factory = factory;
        this.set = new TreeSet<>(comparator);
        this.persisted = persisted;
    }
    
    /**
     * Create an unpersisted sorted set (still in memory)
     * 
     * @param set
     * @param handler
     */
    public FileSortedSet(SortedSet<E> set, TypedSortedSetFileHandler handler, FileSortedSetFactory factory) {
        this.handler = handler;
        this.factory = factory;
        this.set = new TreeSet<>(set);
        this.persisted = false;
    }
    
    /**
     * Create an sorted set out of another sorted set. If persist is true, then the set will be directly persisted using the set's iterator which avoid pulling
     * all of its entries into memory at once.
     *
     * @param set
     * @param handler
     */
    public FileSortedSet(SortedSet<E> set, TypedSortedSetFileHandler handler, FileSortedSetFactory factory, boolean persist) throws IOException {
        this.handler = handler;
        this.factory = factory;
        if (!persist) {
            this.set = new TreeSet<>(set);
            this.persisted = false;
        } else {
            this.set = new TreeSet<>(set.comparator());
            persist(set, handler);
            persisted = true;
        }
    }
    
    /**
     * This will dump the set to the file, making the set "persisted"
     *
     * @throws IOException
     */
    public void persist() throws IOException {
        persist(this.handler);
    }
    
    /**
     * This will dump the set to the file, making the set "persisted"
     *
     * @throws IOException
     */
    public void persist(TypedSortedSetFileHandler handler) throws IOException {
        if (!persisted) {
            persist(this.set, handler);
            this.set.clear();
            persisted = true;
        }
    }
    
    /**
     * This will dump the set to a file, making the set "persisted" The implementation is expected to wrap the handler with a TypedSortedSetFileHandler and the
     * call persist(TypedSortedSetFileHandler handler)
     * 
     * @param handler
     * @throws IOException
     */
    public abstract void persist(SortedSetFileHandler handler) throws IOException;
    
    /**
     * Persist the supplied set to a file as defined by this classes sorted set file handler.
     */
    private void persist(SortedSet<E> set, TypedSortedSetFileHandler handler) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Persisting " + handler);
        }
        
        long start = System.currentTimeMillis();
        try {
            // assign the passed in file handler
            // if we can't persist, we will reset to null
            this.handler = handler;
            
            int actualSize = 0;
            List<E> firstOneHundred = new ArrayList<>();
            SortedSetOutputStream<E> stream = handler.getOutputStream();
            try {
                for (E t : set) {
                    stream.writeObject(t);
                    if (firstOneHundred.size() < 100) {
                        firstOneHundred.add(t);
                    }
                    actualSize++;
                }
                stream.writeSize(actualSize);
            } finally {
                stream.close();
            }
            // verify we wrote at least the size....
            if (handler.getSize() == 0) {
                throw new IOException("Failed to verify file existence");
            }
            // now verify the first 100 objects were written correctly
            SortedSetInputStream<E> inStream = handler.getInputStream();
            try {
                int count = 0;
                for (E t : firstOneHundred) {
                    count++;
                    E input = inStream.readObject();
                    if (!equals(t, input)) {
                        throw new IOException("Failed to verify element " + count + " was written");
                    }
                }
            } finally {
                inStream.close();
            }
            
            // now verify the size was written at the end
            inStream = handler.getInputStream();
            int test = inStream.readSize();
            if (test != actualSize) {
                throw new IOException("Failed to verify file size was written");
            }
        } catch (IOException e) {
            handler.deleteFile();
            this.handler = null;
            throw e;
        }
        
        if (log.isDebugEnabled()) {
            long delta = System.currentTimeMillis() - start;
            log.debug("Persisting " + handler + " took " + delta + "ms");
        }
    }
    
    /**
     * Read the size from the file which is in the last 4 bytes.
     * 
     * @return the size (in terms of objects)
     * @throws IOException
     */
    private int readSize() throws IOException {
        long bytesToSkip = handler.getSize() - 4;
        SortedSetInputStream<E> inStream = handler.getInputStream();
        try {
            return inStream.readSize();
        } finally {
            inStream.close();
        }
    }
    
    /**
     * This will read the file into an in-memory set, making this file "unpersisted"
     * 
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public void load() throws IOException, ClassNotFoundException {
        if (persisted) {
            try {
                SortedSetInputStream<E> stream = getBoundedFileHandler().getInputStream(getStart(), getEnd());
                try {
                    E obj = stream.readObject();
                    while (obj != null) {
                        set.add(obj);
                        obj = stream.readObject();
                    }
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IOException("Unable to read file into a complete set", e);
            }
            handler.deleteFile();
            persisted = false;
        }
    }
    
    protected E readObject(ObjectInputStream stream) {
        try {
            return (E) stream.readObject();
        } catch (Exception E) {
            return null;
        }
    }
    
    protected void writeObject(ObjectOutputStream stream, E obj) throws IOException {
        stream.writeObject(obj);
    }
    
    /**
     * Is this set persisted?
     */
    public boolean isPersisted() {
        return persisted;
    }
    
    /**
     * Get the size of the set. Note if the set has been persisted, then this may be an upper bound on the size.
     * 
     * @return the size upper bound
     */
    @Override
    public int size() {
        if (persisted) {
            if (isSubset()) {
                throw new IllegalStateException("Unable to determine size of a subset of a persisted set.  Please call load() first.");
            }
            try {
                return readSize();
            } catch (Exception e) {
                throw new IllegalStateException("Unable to get size from file", e);
            }
        } else {
            return set.size();
        }
    }
    
    @Override
    public boolean isEmpty() {
        // must attempt to read the first element to be sure if persisted
        try {
            first();
            return false;
        } catch (NoSuchElementException e) {
            return true;
        }
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public boolean contains(Object o) {
        if (persisted) {
            E t = (E) o;
            for (Iterator<E> it = iterator(); it.hasNext();) {
                if (it.hasNext()) {
                    E next = it.next();
                    if (equals(next, t)) {
                        return true;
                    }
                }
            }
            return false;
        } else {
            return set.contains(o);
        }
    }
    
    @Override
    public Iterator<E> iterator() {
        if (persisted) {
            return new FileIterator();
        } else {
            return set.iterator();
        }
    }
    
    @Override
    public Object[] toArray() {
        if (persisted) {
            try {
                SortedSetInputStream<E> stream = getBoundedFileHandler().getInputStream(getStart(), getEnd());
                try {
                    Object[] data = new Object[readSize()];
                    int index = 0;
                    E obj = stream.readObject();
                    while (obj != null) {
                        data[index++] = obj;
                        obj = stream.readObject();
                    }
                    if (index < data.length) {
                        Object[] dataCpy = new Object[index];
                        System.arraycopy(data, 0, dataCpy, 0, index);
                        data = dataCpy;
                    }
                    return data;
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read file into a complete set", e);
            }
        } else {
            return set.toArray();
        }
    }
    
    @SuppressWarnings({"unchecked"})
    @Override
    public <T> T[] toArray(T[] a) {
        if (persisted) {
            try {
                SortedSetInputStream<E> stream = getBoundedFileHandler().getInputStream(getStart(), getEnd());
                try {
                    T[] data = a;
                    int index = 0;
                    T obj = (T) stream.readObject();
                    while (obj != null) {
                        if (index > data.length) {
                            T[] dataCpy = (T[]) (Array.newInstance(a.getClass().getComponentType(), data.length * 2));
                            System.arraycopy(data, 0, dataCpy, 0, data.length);
                            data = dataCpy;
                        }
                        data[index++] = obj;
                        obj = (T) stream.readObject();
                    }
                    // if not resized
                    if (data == a) {
                        // ensure extra elements are set to null
                        for (; index < data.length; index++) {
                            data[index] = null;
                        }
                    } else if (index < data.length) {
                        T[] dataCpy = (T[]) (Array.newInstance(a.getClass().getComponentType(), index));
                        System.arraycopy(data, 0, dataCpy, 0, index);
                        data = dataCpy;
                    }
                    return data;
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read file into a complete set", e);
            }
        } else {
            return set.toArray(a);
        }
    }
    
    @Override
    public boolean add(E e) {
        if (persisted) {
            throw new IllegalStateException("Cannot add an element to a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.add(e);
        }
    }
    
    @Override
    public boolean remove(Object o) {
        if (persisted) {
            throw new IllegalStateException("Cannot remove an element to a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.remove(o);
        }
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public boolean containsAll(Collection<?> c) {
        if (c.isEmpty()) {
            return true;
        }
        if (persisted) {
            try {
                SortedSet<E> all = new TreeSet<>(set.comparator());
                for (Object o : c) {
                    all.add((E) o);
                }
                SortedSetInputStream<E> stream = getBoundedFileHandler().getInputStream(getStart(), getEnd());
                try {
                    E obj = stream.readObject();
                    while (obj != null) {
                        if (all.remove(obj)) {
                            if (all.isEmpty()) {
                                return true;
                            }
                        }
                        obj = stream.readObject();
                    }
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read file into a complete set", e);
            }
            return false;
        } else {
            return set.containsAll(c);
        }
    }
    
    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (persisted) {
            throw new IllegalStateException("Unable to add to a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.addAll(c);
        }
    }
    
    @Override
    public boolean retainAll(Collection<?> c) {
        if (persisted) {
            throw new IllegalStateException("Unable to modify a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.retainAll(c);
        }
    }
    
    @Override
    public boolean removeAll(Collection<?> c) {
        if (persisted) {
            throw new IllegalStateException("Unable to remove from a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.removeAll(c);
        }
    }
    
    @Override
    public void clear() {
        if (persisted) {
            handler.deleteFile();
            persisted = false;
        } else {
            set.clear();
        }
    }
    
    @Override
    public Comparator<? super E> comparator() {
        return set.comparator();
    }
    
    @Override
    public SortedSet<E> subSet(E fromElement, E toElement) {
        return factory.newInstance(this, fromElement, toElement);
    }
    
    @Override
    public SortedSet<E> headSet(E toElement) {
        return factory.newInstance(this, null, toElement);
    }
    
    @Override
    public SortedSet<E> tailSet(E fromElement) {
        return factory.newInstance(this, fromElement, null);
    }
    
    @Override
    public E first() {
        boolean gotFirst = false;
        E first = null;
        if (persisted) {
            try {
                SortedSetInputStream<E> stream = getBoundedFileHandler().getInputStream(getStart(), getEnd());
                try {
                    first = stream.readObject();
                    gotFirst = true;
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                QueryException qe = new QueryException(DatawaveErrorCode.FETCH_FIRST_ELEMENT_ERROR, e);
                throw (new IllegalStateException(qe));
            }
        } else if (!set.isEmpty()) {
            first = set.first();
            gotFirst = true;
        }
        if (!gotFirst) {
            QueryException qe = new QueryException(DatawaveErrorCode.FETCH_FIRST_ELEMENT_ERROR);
            throw (NoSuchElementException) (new NoSuchElementException().initCause(qe));
        } else {
            return first;
        }
    }
    
    @Override
    public E last() {
        boolean gotLast = false;
        E last = null;
        if (persisted) {
            try {
                SortedSetInputStream<E> stream = getBoundedFileHandler().getInputStream(getStart(), getEnd());
                try {
                    last = stream.readObject();
                    E next = stream.readObject();
                    while (next != null) {
                        last = next;
                        next = stream.readObject();
                    }
                    gotLast = true;
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to get last from file", e);
            }
        } else if (!set.isEmpty()) {
            last = set.last();
            gotLast = true;
        }
        if (!gotLast) {
            QueryException qe = new QueryException(DatawaveErrorCode.FETCH_LAST_ELEMENT_ERROR);
            throw (NoSuchElementException) (new NoSuchElementException().initCause(qe));
        } else {
            return last;
        }
    }
    
    @Override
    public String toString() {
        if (persisted) {
            return handler.toString();
        } else {
            return set.toString();
        }
    }
    
    /**
     * Extending classes must implement cloneable
     *
     * @return A clone
     */
    public FileSortedSet<E> clone() {
        return factory.newInstance(this);
    }
    
    /********* Some utilities ***********/
    
    private boolean equals(E o1, E o2) {
        if (o1 == null) {
            return o2 == null;
        } else if (o2 == null) {
            return false;
        } else {
            if (set.comparator() == null) {
                return o1.equals(o2);
            } else {
                return set.comparator().compare(o1, o2) == 0;
            }
        }
    }
    
    private E getStart() {
        return (isSubset() ? range[0] : null);
    }
    
    private E getStart(E from) {
        E start = getStart();
        if (start == null) {
            return from;
        } else if (from == null) {
            return start;
        } else if (compare(start, from) > 0) {
            return start;
        } else {
            return from;
        }
    }
    
    private E getEnd() {
        return (isSubset() ? range[1] : null);
    }
    
    private E getEnd(E to) {
        E end = getEnd();
        if (end == null) {
            return to;
        } else if (to == null) {
            return end;
        } else if (compare(end, to) < 0) {
            return end;
        } else {
            return to;
        }
    }
    
    private boolean isSubset() {
        return (range != null);
    }
    
    private int compare(E a, E b) {
        if (this.set.comparator() != null) {
            return this.set.comparator().compare(a, b);
        } else {
            return ((Comparable<E>) a).compareTo(b);
        }
    }
    
    public BoundedTypedSortedSetFileHandler<E> getBoundedFileHandler() {
        return new DefaultBoundedTypedSortedSetFileHandler();
    }
    
    /********* Some sub classes ***********/
    
    /**
     * This is the iterator for a persisted FileSortedSet
     * 
     * 
     * 
     */
    protected class FileIterator implements Iterator<E> {
        private SortedSetInputStream<E> stream = null;
        private E next = null;
        
        public FileIterator() {
            try {
                this.stream = getBoundedFileHandler().getInputStream(getStart(), getEnd());
                next = stream.readObject();
                if (next == null) {
                    cleanup();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read file", e);
            }
        }
        
        public void cleanup() {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception e) {
                    // we tried...
                }
                stream = null;
            }
        }
        
        @Override
        public boolean hasNext() {
            return (next != null);
        }
        
        @Override
        public E next() {
            if (!hasNext()) {
                QueryException qe = new QueryException(DatawaveErrorCode.FETCH_NEXT_ELEMENT_ERROR);
                throw (NoSuchElementException) (new NoSuchElementException().initCause(qe));
            }
            try {
                E rtrn = next;
                next = stream.readObject();
                if (next == null) {
                    cleanup();
                }
                return rtrn;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to get next element from file", e);
            }
        }
        
        @Override
        public void remove() {
            throw new UnsupportedOperationException("Cannot remove elements from a persisted file.  Please call load() first.");
        }
        
        @Override
        protected void finalize() throws Throwable {
            cleanup();
            super.finalize();
        }
        
    }
    
    /**
     * An interface for a sorted set factory
     * 
     * @param <E>
     */
    public interface FileSortedSetFactory<E> {
        /**
         * factory method
         *
         * @param other
         */
        FileSortedSet<E> newInstance(FileSortedSet<E> other);
        
        /**
         * factory method
         *
         * @param other
         * @param from
         * @param to
         */
        FileSortedSet<E> newInstance(FileSortedSet<E> other, E from, E to);
        
        /**
         * factory method
         *
         * @param handler
         * @param persisted
         * @return a new instance
         */
        FileSortedSet<E> newInstance(SortedSetFileHandler handler, boolean persisted);
        
        /**
         * Factory method
         *
         * @param comparator
         * @param handler
         * @param persisted
         * @return a new instance
         */
        FileSortedSet<E> newInstance(Comparator<? super E> comparator, SortedSetFileHandler handler, boolean persisted);
        
        /**
         * Create an unpersisted sorted set (still in memory)
         *
         * @param set
         * @param handler
         * @return a new instance
         */
        FileSortedSet<E> newInstance(SortedSet<E> set, SortedSetFileHandler handler);
        
        /**
         * factory method
         *
         * @param set
         * @param handler
         * @param persist
         * @return a new instance
         * @throws IOException
         */
        FileSortedSet<E> newInstance(SortedSet<E> set, SortedSetFileHandler handler, boolean persist) throws IOException;
    }
    
    /**
     * A sorted set input stream
     * 
     * @param <E>
     */
    public interface SortedSetInputStream<E> {
        E readObject() throws IOException;
        
        int readSize() throws IOException;
        
        void close();
    }
    
    /**
     * A sorted set output stream
     * 
     * @param <E>
     */
    public interface SortedSetOutputStream<E> {
        void writeObject(E obj) throws IOException;
        
        void writeSize(int size) throws IOException;
        
        void close() throws IOException;
    }
    
    /**
     * A factory that will provide the input stream and output stream to the same underlying file.
     *
     */
    public interface SortedSetFileHandler {
        /**
         * Return the input stream
         *
         * @return the input stream
         * @throws IOException
         */
        InputStream getInputStream() throws IOException;
        
        /**
         * Return the output stream
         *
         * @return the sorted set output stream
         * @throws IOException
         */
        OutputStream getOutputStream() throws IOException;
        
        long getSize();
        
        void deleteFile();
    }
    
    /**
     * A factory that will provide the input stream and output stream to the same underlying file.
     *
     */
    public interface TypedSortedSetFileHandler<E> {
        /**
         * Return the input stream
         *
         * @return the input stream
         * @throws IOException
         */
        SortedSetInputStream<E> getInputStream() throws IOException;
        
        /**
         * Return the output stream
         *
         * @return the sorted set output stream
         * @throws IOException
         */
        SortedSetOutputStream<E> getOutputStream() throws IOException;
        
        long getSize();
        
        void deleteFile();
    }
    
    /**
     * A factory that will provide the input stream and output stream to the same underlying file. An additional input stream method allows for creating a
     * stream subset.
     *
     */
    public interface BoundedTypedSortedSetFileHandler<E> extends TypedSortedSetFileHandler<E> {
        /**
         * Return the input stream
         *
         * @return the input stream
         * @param start
         * @param end
         * @throws IOException
         */
        SortedSetInputStream<E> getInputStream(E start, E end) throws IOException;
    }
    
    /**
     * A default implementation for a bounded typed sorted set
     */
    public class DefaultBoundedTypedSortedSetFileHandler implements BoundedTypedSortedSetFileHandler<E> {
        @Override
        public SortedSetInputStream<E> getInputStream(E start, E end) throws IOException {
            if (handler instanceof FileSortedSet.BoundedTypedSortedSetFileHandler) {
                return ((BoundedTypedSortedSetFileHandler) handler).getInputStream(start, end);
            } else {
                return new BoundedInputStream(handler.getInputStream(), start, end);
            }
        }
        
        @Override
        public SortedSetInputStream<E> getInputStream() throws IOException {
            return handler.getInputStream();
        }
        
        @Override
        public SortedSetOutputStream<E> getOutputStream() throws IOException {
            return handler.getOutputStream();
        }
        
        @Override
        public long getSize() {
            return handler.getSize();
        }
        
        @Override
        public void deleteFile() {
            handler.deleteFile();
        }
    }
    
    /**
     * An input stream that supports bounding the objects. Used when the underlying stream does not already support bounding.
     */
    public class BoundedInputStream implements SortedSetInputStream<E> {
        private SortedSetInputStream<E> delegate;
        private E from;
        private E to;
        
        public BoundedInputStream(SortedSetInputStream<E> stream, E from, E to) {
            this.delegate = stream;
            this.from = from;
            this.to = to;
        }
        
        @Override
        public E readObject() throws IOException {
            E o = delegate.readObject();
            while ((o != null) && (from != null) && (compare(o, from) < 0)) {
                o = delegate.readObject();
            }
            if (o == null || (to != null && compare(o, to) >= 0)) {
                return null;
            } else {
                return o;
            }
        }
        
        @Override
        public int readSize() throws IOException {
            return delegate.readSize();
        }
        
        @Override
        public void close() {
            delegate.close();
        }
    }
    
}
