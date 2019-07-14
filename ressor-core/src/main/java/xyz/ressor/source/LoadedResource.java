package xyz.ressor.source;

import java.io.InputStream;

public class LoadedResource {
    /**
     * The InputStream of the read resource data
     */
    private final InputStream inputStream;
    /**
     * The last modified date in the milliseconds.
     * For the file it's read from the FS metadata, in HTTP from Last-Modified header, etc.
     */
    private final long lastModifiedMillis;
    /**
     * The unique string that would uniquely identify the resource in the best way.
     * FS path in case of file, URL in case of HTTP resource, etc.
     */
    private final String resourceId;

    public LoadedResource(InputStream inputStream, long lastModifiedMillis, String resourceId) {
        this.inputStream = inputStream;
        this.lastModifiedMillis = lastModifiedMillis;
        this.resourceId = resourceId;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    public String getResourceId() {
        return resourceId;
    }
}
