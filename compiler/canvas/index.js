/**
 * Constructor for a canvas.
 * @param {string} id - id of the canvas.
 * @param {string} query - a SQL query. This query extracts the raw data this canvas is based on.
 * @param {function} placement - a javascript function calculating the placement of objects. See spec api for more details.
 * @param {function} transform - a javascript function calculating data transforms on raw data.
 * @param {function} rendering - a javascript function that produces an svg. Its input includes viewport center coordinates, the results of the placement and data transform functions. See spec api for more details.
 * @param {boolean} separable - whether the calculation of placement and transform is per-tuple based. If yes, the input to the placement and transform is a single tuple. Otherwise their input is the whole query result.
 * @constructor
 */
function Canvas(id, query, placement, transform, rendering, separable)
{
    // type check
    if (typeof placement !== "function")
        throw new Error("placement must be a javascript function.");
    if (typeof transform !== "function")
        throw new Error("transform must be a javascript function.");
    if (typeof rendering !== "function")
        throw new Error("rendering must be a javascript function.");
    if (typeof separable !== "boolean")
        throw new Error("separable must be boolean.");

    // assign fields
    this.id = String(id);
    this.query = String(query);
    this.placement = placement;
    this.transform = transform;
    this.rendering = rendering;
    this.separable = separable;
}


/**
 * Constructor for a layered canvas.
 * @param {string} id - id of the layered canvas.
 * @param {array} subCanvases - an array of ids of the canvases to be layered.
 * @constructor
 */
function LayeredCanvas(id, subCanvases)
{
    // id
    this.id = id;

    // canvases
    this.subCanvases = subCanvases;
}


// define prototype functions


// exports
module.exports = {
    Canvas : Canvas,
    LayeredCanvas : LayeredCanvas
}