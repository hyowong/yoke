package com.jetdrone.vertx.yoke.extras.middleware;

import com.jetdrone.vertx.yoke.Middleware;
import com.jetdrone.vertx.yoke.middleware.Router;
import com.jetdrone.vertx.yoke.middleware.YokeRequest;
import com.jetdrone.vertx.yoke.extras.store.Store;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO: content negotiation
// TODO: extend Router?
public class JsonRestStore {

    // GET /
    public static final int QUERY =     1;
    // GET /:id
    public static final int READ =      2;
    // PUT /:id
    public static final int UPDATE =    4;
    // PATCH /:id
    public static final int APPEND =    8;
    // POST /
    public static final int CREATE =    16;
    // DELETE /:id
    public static final int DELETE =    32;

    private final String sortParam;
    private final Pattern sortPattern = Pattern.compile("sort\\((.+)\\)");

    private final Router router;
    private final Store store;

    private static final Middleware NOT_ALLOWED = new Middleware() {
        @Override
        public void handle(YokeRequest request, Handler<Object> next) {
            next.handle(405);
        }
    };

    public JsonRestStore(Router router, Store store) {
        this.router = router;
        this.store = store;
        this.sortParam = null;
    }

    private boolean isAllowed(int operation, int allowedOperations) {
        return (allowedOperations & operation) == operation;
    }


    public JsonRestStore rest(String resource, String entity) {
        return rest(resource, entity, QUERY + READ + UPDATE + APPEND + CREATE + DELETE);
    }

    public JsonRestStore rest(String resource, String entity, int allowedOperations) {
        // build the resource url
        String resourcePath = entity.endsWith("/") ? resource.substring(0, resource.length() - 1) : resource;

        if (isAllowed(QUERY, allowedOperations)) {
            router.get(resourcePath, query(entity));
        } else {
            router.get(resourcePath, NOT_ALLOWED);
        }

        if (isAllowed(READ, allowedOperations)) {
            router.get(resourcePath + "/:" + entity, read(entity));
        } else {
            router.get(resourcePath + "/:" + entity, NOT_ALLOWED);
        }

        if (isAllowed(UPDATE, allowedOperations)) {
            router.put(resourcePath + "/:" + entity, update(entity));
        } else {
            router.put(resourcePath + "/:" + entity, NOT_ALLOWED);
        }

        if (isAllowed(APPEND, allowedOperations)) {
            // shortcut for patch (as by Dojo Toolkit)
            router.post(resourcePath + "/:" + entity, append(entity));
            router.patch(resourcePath + "/:" + entity, append(entity));
        } else {
            // shortcut for patch (as by Dojo Toolkit)
            router.post(resourcePath + "/:" + entity, NOT_ALLOWED);
            router.patch(resourcePath + "/:" + entity, NOT_ALLOWED);
        }

        if (isAllowed(CREATE, allowedOperations)) {
            router.post(resourcePath, create(entity));
        } else {
            router.post(resourcePath, NOT_ALLOWED);
        }

        if (isAllowed(DELETE, allowedOperations)) {
            router.delete(resourcePath + "/:" + entity, delete(entity));
        } else {
            router.delete(resourcePath + "/:" + entity, NOT_ALLOWED);
        }

        return this;
    }

    private Middleware delete(final String idName) {
        return new Middleware() {
            @Override
            public void handle(final YokeRequest request, final Handler<Object> next) {
                // get the real id from the params multimap
                final String id = request.params().get(idName);

                store.delete(idName, id, new AsyncResultHandler<Number>() {
                    @Override
                    public void handle(AsyncResult<Number> event) {
                        if (event.failed()) {
                            next.handle(event.cause());
                            return;
                        }

                        if (event.result() == 0) {
                            request.response().setStatusCode(404);
                            request.response().end();
                        } else {
                            request.response().setStatusCode(204);
                            request.response().end();
                        }
                    }
                });
            }
        };
    }

    private Middleware create(final String idName) {
        return new Middleware() {
            @Override
            public void handle(final YokeRequest request, final Handler<Object> next) {
                JsonObject item = request.jsonBody();

                if (item == null) {
                    next.handle("Body must be JSON");
                    return;
                }

                store.create(idName, item, new AsyncResultHandler<String>() {
                    @Override
                    public void handle(AsyncResult<String> event) {
                        if (event.failed()) {
                            next.handle(event.cause());
                            return;
                        }
                        request.response().putHeader("location", request.path() + "/" + event.result());
                        request.response().setStatusCode(201);
                        request.response().end();
                    }
                });
            }
        };
    }

    private Middleware append(final String idName) {
        return new Middleware() {
            @Override
            public void handle(final YokeRequest request, final Handler<Object> next) {
                // get the real id from the params multimap
                final String id = request.params().get(idName);

                store.read(idName, id, new AsyncResultHandler<JsonObject>() {
                    @Override
                    public void handle(AsyncResult<JsonObject> event) {
                        if (event.failed()) {
                            next.handle(event.cause());
                            return;
                        }

                        if (event.result() == null) {
                            // does not exist, returns 404
                            request.response().setStatusCode(404);
                            request.response().end();
                        } else {
                            // merge existing json with incoming one
                            Boolean overwrite = null;

                            if ("*".equals(request.getHeader("if-match"))) {
                                overwrite = true;
                            }

                            if ("*".equals(request.getHeader("if-none-match"))) {
                                overwrite = false;
                            }

                            // TODO: handle overwrite
                            final JsonObject obj = event.result();
                            obj.mergeIn(request.jsonBody());

                            // update back to the db
                            store.update(idName, id, obj, new AsyncResultHandler<Number>() {
                                @Override
                                public void handle(AsyncResult<Number> event) {
                                    if (event.failed()) {
                                        next.handle(event.cause());
                                        return;
                                    }

                                    if (event.result() == 0) {
                                        // nothing was updated
                                        request.response().setStatusCode(404);
                                        request.response().end();
                                    } else {
                                        request.response().setStatusCode(204);
                                        request.response().end();
                                    }
                                }
                            });
                        }
                    }
                });
            }
        };
    }

    private Middleware update(final String idName) {
        return new Middleware() {
            @Override
            public void handle(final YokeRequest request, final Handler<Object> next) {
                JsonObject item = request.jsonBody();

                if (item == null) {
                    next.handle("Body must be JSON");
                    return;
                }

                // get the real id from the params multimap
                String id = request.params().get(idName);

                store.update(idName, id, item, new AsyncResultHandler<Number>() {
                    @Override
                    public void handle(AsyncResult<Number> event) {
                        if (event.failed()) {
                            next.handle(event.cause());
                            return;
                        }

                        if (event.result() == 0) {
                            // nothing was updated
                            request.response().setStatusCode(404);
                            request.response().end();
                        } else {
                            request.response().setStatusCode(204);
                            request.response().end();
                        }
                    }
                });
            }
        };
    }


    private Middleware query(final String idName) {
        // range pattern
        final Pattern rangePattern = Pattern.compile("items=(\\d+)-(\\d+)");

        return new Middleware() {
            @Override
            public void handle(final YokeRequest request, final Handler<Object> next) {
                // parse ranges
                final String range = request.getHeader("range");
                final String start, end;
                if (range != null) {
                    Matcher m = rangePattern.matcher(range);
                    if (m.matches()) {
                        start = m.group(1);
                        end = m.group(2);
                    } else {
                        start = null;
                        end = null;
                    }
                } else {
                    start = null;
                    end = null;
                }

                // parse query
                final JsonObject dbquery = new JsonObject();
                final JsonObject dbsort = new JsonObject();
                for (Map.Entry<String, String> entry : request.params()) {
                    String[] sortArgs;
                    // parse sort
                    if (sortParam == null) {
                        Matcher sort = sortPattern.matcher(entry.getKey());

                        if (sort.matches()) {
                            sortArgs = sort.group(1).split(",");
                            for (String arg : sortArgs) {
                                if (arg.charAt(0) == '+' || arg.charAt(0) == ' ') {
                                    dbsort.putNumber(arg.substring(1), 1);
                                } else if (arg.charAt(0) == '-') {
                                    dbsort.putNumber(arg.substring(1), -1);
                                }
                            }
                            continue;
                        }
                    } else {
                        if (sortParam.equals(entry.getKey())) {
                            sortArgs = entry.getValue().split(",");
                            for (String arg : sortArgs) {
                                if (arg.charAt(0) == '+' || arg.charAt(0) == ' ') {
                                    dbsort.putNumber(arg.substring(1), 1);
                                } else if (arg.charAt(0) == '-') {
                                    dbsort.putNumber(arg.substring(1), -1);
                                }
                            }
                            continue;
                        }
                    }
                    dbquery.putString(entry.getKey(), entry.getValue());
                }

                store.query(idName, dbquery, start, end, dbsort, new AsyncResultHandler<JsonArray>() {
                    @Override
                    public void handle(final AsyncResult<JsonArray> query) {
                        if (query.failed()) {
                            next.handle(query.cause());
                            return;
                        }

                        if (range != null) {
                            // need to send the content-range with totals
                            store.count(idName, dbquery, new AsyncResultHandler<Number>() {
                                @Override
                                public void handle(AsyncResult<Number> count) {
                                    if (count.failed()) {
                                        next.handle(count.cause());
                                        return;
                                    }

                                    // TODO: end should be start + number of results
                                    request.response().putHeader("content-range", "items " + start + "-" + end + "/" + count.result());
                                    request.response().end(query.result());
                                }
                            });
                            return;
                        }

                        request.response().end(query.result());
                    }
                });
            }
        };
    }

    private Middleware read(final String idName) {
        return new Middleware() {
            @Override
            public void handle(final YokeRequest request, final Handler<Object> next) {
                // get the real id from the params multimap
                String id = request.params().get(idName);

                store.read(idName, id, new AsyncResultHandler<JsonObject>() {
                    @Override
                    public void handle(AsyncResult<JsonObject> event) {
                        if (event.failed()) {
                            next.handle(event.cause());
                            return;
                        }

                        if (event.result() == null) {
                            // does not exist, returns 404
                            request.response().setStatusCode(404);
                            request.response().end();
                        } else {
                            request.response().end(event.result());
                        }
                    }
                });
            }
        };
    }
}
