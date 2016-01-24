package diskCacheV111.poolManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import diskCacheV111.poolManager.PoolSelectionUnit.SelectionPoolGroup;

class PGroup extends PoolCore implements SelectionPoolGroup {
    private static final long serialVersionUID = 3883973457610397314L;
    final Map<String, Pool> _poolList = new ConcurrentHashMap<>();

    PGroup(String name, boolean resilient) {
        super(name);
        this.resilient = resilient;
    }

    @Override
    public boolean isResilient() {
        return resilient;
    }

    @Override
    public String toString() {
        return String.format(
                        "%s %s (links=%s; pools=%s; resilient=%s)",
                        super.toString(), getName(), _linkList.size(),
                        _poolList.size(), resilient);
    }

    private String[] getPools() {
        return _poolList.keySet().toArray(new String[_poolList.size()]);
    }
}
