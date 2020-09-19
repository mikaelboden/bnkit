package dat.phylo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IdxTreeTest {

    @Test
    void createPrunedTree() {
        Set<Integer> s = IdxTree.getIndices(false, new Object[] {true, false, true, true, false, true});
        IdxTree pruned = IdxTree.createPrunedTree(defaultTree, s);
        assertEquals(defaultTree.getSize() - 2, pruned.getSize());
        assertEquals(5, pruned.getRoots().length); // confirmed by manual inspection (MB)
        s = IdxTree.getIndices(false, new Object[] {false, true}); // remove root only, two subtrees should form
        pruned = IdxTree.createPrunedTree(defaultTree, s);
        assertEquals(defaultTree.getSize() - 1, pruned.getSize());
        assertEquals(2, pruned.getRoots().length);
        s = IdxTree.getIndices(false, new Object[] {true, true, true, false}); // remove a leaf node only, one tree still
        pruned = IdxTree.createPrunedTree(defaultTree, s);
        assertEquals(defaultTree.getSize() - 1, pruned.getSize());
        assertEquals(1, pruned.getRoots().length);
        s = IdxTree.getIndices(false, new Object[] {true, true, true, true, true, true, true, false, false, false}); // remove two leafs and their ancestor, one tree still
        pruned = IdxTree.createPrunedTree(defaultTree, s);
        assertEquals(defaultTree.getSize() - 3, pruned.getSize());
        assertEquals(1, pruned.getRoots().length);
        s = IdxTree.getIndices(false, new Object[] {true, true, true, true, true, true, true, true, false, false}); // remove two leafs, one tree still
        pruned = IdxTree.createPrunedTree(defaultTree, s);
        assertEquals(defaultTree.getSize() - 2, pruned.getSize());
        assertEquals(1, pruned.getRoots().length);
        s = IdxTree.getIndices(false, new Object[] {true, true, true, true, true, true, true, false, true, true}); // remove the ancestor of two leafs, three trees...
        pruned = IdxTree.createPrunedTree(defaultTree, s);
        assertEquals(defaultTree.getSize() - 1, pruned.getSize());
        assertEquals(3, pruned.getRoots().length);
        s = IdxTree.getIndices(false, new Object[] {true, true, false, true, true, false, true, false, false, true, true, true, true, false});
        pruned = IdxTree.createPrunedTree(defaultTree, s);
        assertEquals(defaultTree.getSize() - 5, pruned.getSize());
        assertEquals(6, pruned.getRoots().length); // did not check manually!
        s = IdxTree.getIndices(false, new Object[] {false, true, true, true, false, true, true, false, true, false, false, true, true, true, true, false});
        pruned = IdxTree.createPrunedTree(defaultTree, s);
        assertEquals(defaultTree.getSize() - 6, pruned.getSize());
        assertEquals(7, pruned.getRoots().length); // did not check manually!
    }

    @Test
    void createSubtree() {
        IdxTree subtree = IdxTree.createSubtree(defaultTree, 0);
        assertEquals(subtree.getSize(), defaultTree.getSize());
        for (int bp = 0; bp < defaultTree.getSize(); bp ++) {
            IdxTree pruned = IdxTree.createPrunedTree(defaultTree, Collections.singleton(bp));
            System.out.println(pruned);
            int[] roots = pruned.getRoots();
            //System.out.print(roots.length + " roots: ");
            int total = 0;
            for (int rootidx : roots) {
                subtree = IdxTree.createSubtree(pruned, rootidx);
                total += subtree.getSize();
                //System.out.print(subtree.getSize() + " ");
            }
            //System.out.println("= " + pruned.getSize());
            assertEquals(pruned.getSize(), total);
        }
    }
    static IdxTree defaultTree = null;

    @BeforeAll
    static void setThingsUp() {
        Tree tree;
        try {
            tree = Tree.load("bnkit/src/test/resources/default.nwk", "newick");
            defaultTree = (IdxTree)tree;
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

}