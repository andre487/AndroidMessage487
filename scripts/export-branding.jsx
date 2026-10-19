// Called by generate-branding.py through Illustrator's AppleScript interface.
(function (sourcePath, outputPath) {
    var previousInteraction = app.userInteractionLevel;
    var source = null;
    var output = null;
    var sizes = {icon: [512, 512], 'icon-round': [512, 512],
                 'status-icon': [24, 24], feature: [1024, 500]};
    try {
        app.userInteractionLevel = UserInteractionLevel.DONTDISPLAYALERTS;
        source = app.open(new File(sourcePath));
        // Validate the whole document before exporting any artboards.
        var indices = {};
        for (var i = 0; i < source.artboards.length; i++) {
            var board = source.artboards[i];
            if (!sizes.hasOwnProperty(board.name)) continue;
            if (indices.hasOwnProperty(board.name)) throw new Error('Duplicate artboard: ' + board.name);
            var bounds = board.artboardRect;
            var size = sizes[board.name];
            if (Math.abs(bounds[2] - bounds[0] - size[0]) > 0.01 ||
                Math.abs(bounds[1] - bounds[3] - size[1]) > 0.01) {
                throw new Error(board.name + ' must be ' + size[0] + 'x' + size[1]);
            }
            indices[board.name] = i;
        }
        var options = new ExportOptionsSVG();
        options.coordinatePrecision = 7;
        options.cssProperties = SVGCSSPropertyLocation.PRESENTATIONATTRIBUTES;
        options.fontType = SVGFontType.OUTLINEFONT;
        options.documentEncoding = SVGDocumentEncoding.UTF8;
        options.preserveEditability = false;
        options.saveMultipleArtboards = true;
        options.artboardRange = '1';
        for (var name in sizes) {
            if (!sizes.hasOwnProperty(name)) continue;
            if (!indices.hasOwnProperty(name)) throw new Error('Missing artboard: ' + name);
            source.activate();
            source.selection = null;
            source.artboards.setActiveArtboardIndex(indices[name]);
            source.selectObjectsOnActiveArtboard();
            var selected = source.selection;
            if (!selected.length) throw new Error('Empty artboard: ' + name);
            var rect = source.artboards[indices[name]].artboardRect;
            output = app.documents.add(DocumentColorSpace.RGB, sizes[name][0], sizes[name][1]);
            var origin = output.artboards[0].artboardRect;
            for (var j = 0; j < selected.length; j++) {
                var before = selected[j].geometricBounds;
                var item = selected[j].duplicate(output.layers[0], ElementPlacement.PLACEATEND);
                var after = item.geometricBounds;
                item.translate(origin[0] + before[0] - rect[0] - after[0],
                               origin[1] + before[1] - rect[1] - after[1]);
            }
            // Expand only export copies; the editable source keeps its shared symbols.
            while (output.symbolItems.length) output.symbolItems[0].breakLink();
            var folder = new Folder(outputPath + '/' + name);
            if (!folder.create() && !folder.exists) throw new Error('Cannot create ' + folder.fsName);
            output.exportFile(new File(folder.fsName + '/export'), ExportType.SVG, options);
            output.close(SaveOptions.DONOTSAVECHANGES);
            output = null;
        }
        return 'Exported four branding artboards';
    } finally {
        if (output) output.close(SaveOptions.DONOTSAVECHANGES);
        if (source) source.close(SaveOptions.DONOTSAVECHANGES);
        app.userInteractionLevel = previousInteraction;
    }
}(arguments[0], arguments[1]));
