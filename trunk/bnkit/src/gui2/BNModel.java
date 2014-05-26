/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package gui2;

import bn.BNet;
import bn.BNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 *
 * @author Study Room
 */
public class BNModel implements Observable{
    private final BNContainer bnc;
    // BNodeMap provides the link between nodes in View and BNodes in Model.
    private HashMap<Object, BNode> BNodeMap = new HashMap<>();
    private List<Object> selectedCells = new ArrayList<>();
    private BNet bnet = new BNet();
    private boolean changed;
    private final Object MUTEX = new Object();
    private List<Observer> observers;
    
    
    public BNContainer getBNC(){
        return bnc;
    }

    public BNModel(){
        this.bnc = new BNContainer();
    }
    
    /**
     * 
     * @return BNodeMap
     */
    public HashMap<Object, BNode> getBNodeMap() {
        return BNodeMap;
    }
    
    public void setBNodeMap(HashMap<Object, BNode> BNodeMap) {
        this.BNodeMap = BNodeMap;
    }

    
    /**
     * Add a Cell/Bnode pair into the model's HashMap.
     * @param key
     * @param entry 
     */
    public void put(Object key, BNode entry) {
        BNodeMap.put(key, entry);
    }
    
    public List<Object> getSelectedCells() {
        return selectedCells;
    }

    public void setSelectedCells(List<Object> selectedCells) {
        this.selectedCells = selectedCells;
    }
    
    public void setSelectedCells(Object selectedCells) {
        this.selectedCells.clear();
        this.selectedCells.add(selectedCells);
    }

    @Override
    public void register(Observer obj) {
        if (obj == null) {
            throw new NullPointerException("Null Observer");
        }
        synchronized (MUTEX) {
            if (!observers.contains(obj)) {
                observers.add(obj);
            }
        }
    }

    @Override
    public void unregister(Observer obj) {
        synchronized (MUTEX) {
            observers.remove(obj);
        }
    }

    @Override
    public void notifyObservers() {
        List<Observer> observersLocal = null;
        // This needs to be thread-safe!
        synchronized (MUTEX) {
            if (!changed) {
                return;
            }
            observersLocal = new ArrayList<>(this.observers);
            this.changed = false;
        }
        for (Observer obj : observersLocal) {
            obj.update();
        }
    }

    @Override
    public Object getUpdate(Observer obj) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
 
    
}

