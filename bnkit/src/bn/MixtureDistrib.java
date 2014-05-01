/*
 * Copyright (C) 2014 M. Boden et al.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package bn;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

/**
 * Mixture of probability distributions.
 * Note that mixtures of mixtures is detected and avoided, leading to a flat mixture.
 * @author mikael
 */
public class MixtureDistrib implements Distrib {

    final Map<Distrib, Double> mixture;
    private double density;
    Random rand = new Random();

    public MixtureDistrib(Distrib d1, double weight1) {
        mixture = new HashMap<>();
        try {
            MixtureDistrib packed = (MixtureDistrib) d1;
            for (Map.Entry<Distrib, Double> entry : packed.mixture.entrySet()) {
                double delta = entry.getValue() * weight1;
                mixture.put(entry.getKey(), delta);
                density += delta;
            }
        } catch (ClassCastException e) {
            mixture.put(d1, weight1);
            density = weight1;
        }
    }
    
    private double addDistribForced(Distrib d2, double weight2) {
        Double prev_weight = mixture.get(d2);
        if (prev_weight == null)
            mixture.put(d2, weight2);
        else
            mixture.put(d2, prev_weight.doubleValue() + weight2);
        density += weight2;
        return density;
    }

    public double addDistrib(Distrib d2, double weight2) {
        try {
            MixtureDistrib packed = (MixtureDistrib) d2;
            for (Map.Entry<Distrib, Double> entry : packed.mixture.entrySet())
                addDistribForced(entry.getKey(), entry.getValue() * weight2);
        } catch (ClassCastException e) {
            addDistribForced(d2, weight2);
        }
        return density;
    }
    
    public boolean hasDistrib(Distrib d2) {
        return mixture.containsKey(d2);
    }
    
    @Override
    public double get(Object value) {
        double p = 0.0;
        for (Map.Entry<Distrib, Double> entry : mixture.entrySet()) {
            p += entry.getKey().get(value) * entry.getValue().doubleValue();
        }
        return p;
    }

    @Override
    public Object sample() {
        double y = rand.nextDouble() * density;
        Distrib current = null;
        double p = 0.0;
        for (Map.Entry<Distrib, Double> entry : mixture.entrySet()) {
            current = entry.getKey();
            p += entry.getValue().doubleValue();
            if (p >= y)
                break;
        }
        if (current == null)
            throw new RuntimeException("Invalid MixtureDistrib");
        return current.sample();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("^" + mixture.size());
        for (Map.Entry<Distrib, Double> entry : mixture.entrySet())
            sb.append("{" + entry.getKey() + "*" + String.format("%3.1e", entry.getValue()) + "}");
        return sb.toString();
    }
    
    public static void main(String[] args) {
        GaussianDistrib gd1 = new GaussianDistrib(0, 1.0);
        System.out.println("gd1 = " + gd1);
        GaussianDistrib gd2 = new GaussianDistrib(1, 0.5);
        System.out.println("gd2 = " + gd2);
        GaussianDistrib gd3 = new GaussianDistrib(-2, 2.5);
        System.out.println("gd3 = " + gd3);
        MixtureDistrib md1 = new MixtureDistrib(gd1, 1.0);
        md1.addDistrib(gd2, 2);
        md1.addDistrib(gd2, 0.5);
        System.out.println("md1 is gd1*1.0 + gd2*2.5 : \n" + md1);
        MixtureDistrib md2 = new MixtureDistrib(md1, 1.0);
        System.out.println("mds2 is md1*1.0 : \n" + md2);
        md2.addDistrib(gd1, 0.5);
        System.out.println("md2 += gd1*0.5 : \n" + md2);
        md2.addDistrib(gd3, 2);
        System.out.println("md2 += gd3*2.0 : \n" + md2);
        md2.addDistrib(md1, 2.0);
        System.out.println("md2 += md1*2.0 : \n" + md2);
        md2.addDistrib(gd1, 1.5);
        System.out.println("md2 += gd1*1.5 : \n" + md2);
        System.out.println("density = " + md2.density);
    }
}
