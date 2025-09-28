package Problems.General.RandomHashSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class RandomHashSet<T> {
    private Random rnd;
    private final List<T> list;
    private final Map<T, Integer> idx;
    private int remaning = 0;
    public RandomHashSet() {
        rnd = new Random();
        list = new ArrayList<>();
        idx = new HashMap<>();
    }

    public boolean insert(T item) throws NullPointerException {
        if(item == null) {
            throw new NullPointerException("Item added cannot be null");
        }
        if(idx.containsKey(item)) {
            // already contains the key.
            return false;
        }
        
        idx.put(item, list.size());
        list.add(item);
        if(remaning < list.size() - 1) { 
            swap(remaning, list.size() - 1); // the elements from [remaning, list.size() - 1 ) are available
        }
        remaning++;
        return true;
    }

    public boolean remove(T item) {
        if(!this.contains(item)) {
            return false;
        }
        int id = idx.get(item);
        if(id < remaning) {
            remaning--;
            swap(id, remaning);
            
            swap(remaning, list.size() - 1);
        } else {
            swap(id, list.size() - 1);
        }

        T item2 = list.remove(list.size() - 1);
        idx.remove(item2);
        return true;
    }

    public T random() {
        if(list.size() == 0) {
            return null;
        }
        if(remaning == 0) {
            remaning = list.size();
        }
        int r = rnd.nextInt(remaning);
        remaning--;
        swap(r, remaning);
        return list.get(remaning);
    }

    public boolean contains(T item) {
        return idx.containsKey(item);
    }

    private void swap(int id1, int id2) {
        if(id1 == id2) return;
        
        T t1 = list.get(id1);
        T t2 = list.get(id2);

        list.set(id1, t2);
        list.set(id2, t1);
        idx.put(t1, id2);
        idx.put(t2, id1);
    }


    public static void main(String[] args) {
        RandomHashSet<Integer> x = new RandomHashSet<>();
        x.insert(0);
        x.insert(1);
        x.insert(2);
        x.insert(3);
        x.insert(4);

        for(int i = 0; i < 10; i++) {
            System.out.println(i + "-th Random Iteration value:" + x.random());
        }
    }

}
