package myfluxo.kernel.pagination;

public enum SortDirection {
    ASC,
    DESC;

    public SortDirection flip() {
        return this == ASC ? DESC : ASC;
    }
}
