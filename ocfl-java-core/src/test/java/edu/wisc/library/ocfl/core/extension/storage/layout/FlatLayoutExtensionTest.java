package edu.wisc.library.ocfl.core.extension.storage.layout;

import edu.wisc.library.ocfl.api.exception.OcflExtensionException;
import edu.wisc.library.ocfl.core.extension.storage.layout.FlatLayoutExtension;
import edu.wisc.library.ocfl.test.OcflAsserts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystems;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FlatLayoutExtensionTest {

    private FlatLayoutExtension ext;

    @BeforeEach
    public void setup() {
        ext = new FlatLayoutExtension();
    }

    @Test
    public void mapObjectIdWhenOnlyContainsSafeChars() {
        var objectId = "obj123";
        assertEquals(objectId, ext.mapObjectId(objectId));
    }

    @Test
    public void mapObjectIdWhenOnlyContainsSpecialChars() {
        var objectId = "..hor_rib:lé-$id";
        assertEquals(objectId, ext.mapObjectId(objectId));
    }

    @Test
    public void failWhenIdContainsPathSeparator() {
        var objectId = "obj" + FileSystems.getDefault().getSeparator().charAt(0) + "123";
        OcflAsserts.assertThrowsWithMessage(OcflExtensionException.class, "path separator", () -> {
            ext.mapObjectId(objectId);
        });
    }

}
