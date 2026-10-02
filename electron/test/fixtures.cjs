const fs = require('node:fs');
const path = require('node:path');
const PART_ID = 'Desktop Fixture/Hull';
function modelLibrary(root) {
  const vertices = [[0,0,0],[1,0,0],[1,1,0],[0,1,0],[0,0,1],[1,0,1],[1,1,1],[0,1,1]];
  const faces = [[0,2,1],[0,3,2],[4,5,6],[4,6,7],[0,1,5],[0,5,4],
    [1,2,6],[1,6,5],[2,3,7],[2,7,6],[3,0,4],[3,4,7]];
  const text = 'solid cube\n' + faces.map(face => 'facet normal 0 0 0\nouter loop\n' +
    face.map(index => 'vertex ' + vertices[index].join(' ')).join('\n') + '\nendloop\nendfacet').join('\n') + '\nendsolid cube\n';
  const part = path.join(root, PART_ID);
  fs.mkdirSync(part, {recursive: true});
  fs.writeFileSync(path.join(part, 'unsupported.stl'), text);
}
module.exports = {modelLibrary, PART_ID};
