# Demo metadata

The SCRUM-60 WOMS demo entry point is the versioned dataset in metadata/examples/woms/ (see ../metadata/examples/woms/README.md).

The files remain under metadata/examples/ so the repository's example validator discovers and checks them. Their Page routes still use /demo/woms/...; this directory is the requested documentation entry point, not a second copy of the metadata.

For editor initialization, the host passes the selected work-order id into page context before rendering /demo/woms/work-orders/edit. The GET load binding and PUT update binding consume the same id. Creating a work order uses POST and does not invoke the load binding.
